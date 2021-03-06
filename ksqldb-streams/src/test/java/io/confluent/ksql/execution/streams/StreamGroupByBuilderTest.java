package io.confluent.ksql.execution.streams;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.execution.builder.KsqlQueryBuilder;
import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.expression.tree.Expression;
import io.confluent.ksql.execution.expression.tree.UnqualifiedColumnReferenceExp;
import io.confluent.ksql.execution.plan.ExecutionStep;
import io.confluent.ksql.execution.plan.ExecutionStepPropertiesV1;
import io.confluent.ksql.execution.plan.KGroupedStreamHolder;
import io.confluent.ksql.execution.plan.KStreamHolder;
import io.confluent.ksql.execution.plan.KeySerdeFactory;
import io.confluent.ksql.execution.plan.PlanBuilder;
import io.confluent.ksql.execution.plan.StreamGroupBy;
import io.confluent.ksql.execution.plan.StreamGroupByKey;
import io.confluent.ksql.execution.util.StructKeyUtil;
import io.confluent.ksql.execution.util.StructKeyUtil.KeyBuilder;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.logging.processing.ProcessingLogContext;
import io.confluent.ksql.logging.processing.ProcessingLogger;
import io.confluent.ksql.logging.processing.ProcessingLoggerFactory;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.PhysicalSchema;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.serde.FormatFactory;
import io.confluent.ksql.serde.FormatInfo;
import io.confluent.ksql.serde.SerdeOption;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.SchemaUtil;
import java.util.List;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KGroupedStream;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.kstream.Predicate;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class StreamGroupByBuilderTest {

  private static final KeyBuilder STRING_KEY_BUILDER = StructKeyUtil
      .keyBuilder(SchemaUtil.ROWKEY_NAME, SqlTypes.STRING);
  private static final LogicalSchema SCHEMA = LogicalSchema.builder()
      .withRowTime()
      .keyColumn(ColumnName.of("K0"), SqlTypes.INTEGER)
      .valueColumn(ColumnName.of("PAC"), SqlTypes.BIGINT)
      .valueColumn(ColumnName.of("MAN"), SqlTypes.STRING)
      .build()
      .withMetaAndKeyColsInValue(false);

  private static final LogicalSchema REKEYED_SCHEMA = LogicalSchema.builder()
      .withRowTime()
      .keyColumn(SchemaUtil.ROWKEY_NAME, SqlTypes.STRING)
      .valueColumns(SCHEMA.value())
      .build();

  private static final PhysicalSchema PHYSICAL_SCHEMA =
      PhysicalSchema.from(SCHEMA, SerdeOption.none());

  private static final PhysicalSchema REKEYED_PHYSICAL_SCHEMA =
      PhysicalSchema.from(REKEYED_SCHEMA, SerdeOption.none());

  private static final List<Expression> GROUP_BY_EXPRESSIONS = ImmutableList.of(
      columnReference("PAC"),
      columnReference("MAN")
  );
  private static final QueryContext SOURCE_CTX =
      new QueryContext.Stacker().push("foo").push("source").getQueryContext();
  private static final QueryContext STEP_CTX =
      new QueryContext.Stacker().push("foo").push("groupby").getQueryContext();
  private static final ExecutionStepPropertiesV1 SOURCE_PROPERTIES
      = new ExecutionStepPropertiesV1(SOURCE_CTX);
  private static final ExecutionStepPropertiesV1 PROPERTIES = new ExecutionStepPropertiesV1(
      STEP_CTX
  );
  private static final io.confluent.ksql.execution.plan.Formats FORMATS = io.confluent.ksql.execution.plan.Formats
      .of(
      FormatInfo.of(FormatFactory.KAFKA.name()),
      FormatInfo.of(FormatFactory.JSON.name()),
      SerdeOption.none()
  );

  @Mock
  private KsqlQueryBuilder queryBuilder;
  @Mock
  private KsqlConfig ksqlConfig;
  @Mock
  private FunctionRegistry functionRegistry;
  @Mock
  private GroupedFactory groupedFactory;
  @Mock
  private ExecutionStep sourceStep;
  @Mock
  private Serde<Struct> keySerde;
  @Mock
  private Serde<GenericRow> valueSerde;
  @Mock
  private Grouped<Struct, GenericRow> grouped;
  @Mock
  private KStream<Struct, GenericRow> sourceStream;
  @Mock
  private KStream<Struct, GenericRow> filteredStream;
  @Mock
  private KGroupedStream<Struct, GenericRow> groupedStream;
  @Captor
  private ArgumentCaptor<Predicate<Struct, GenericRow>> predicateCaptor;
  @Mock
  private ProcessingLogContext processingLogContext;
  @Mock
  private ProcessingLoggerFactory processingLoggerFactory;

  private PlanBuilder planBuilder;
  private StreamGroupBy<Struct> streamGroupBy;
  private StreamGroupByKey streamGroupByKey;

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Before
  @SuppressWarnings("unchecked")
  public void init() {
    when(queryBuilder.getKsqlConfig()).thenReturn(ksqlConfig);
    when(queryBuilder.getFunctionRegistry()).thenReturn(functionRegistry);
    when(queryBuilder.buildKeySerde(any(), any(), any())).thenReturn(keySerde);
    when(queryBuilder.buildValueSerde(any(), any(), any())).thenReturn(valueSerde);
    when(queryBuilder.getProcessingLogContext()).thenReturn(processingLogContext);
    when(queryBuilder.getQueryId()).thenReturn(new QueryId("qid"));
    when(groupedFactory.create(any(), any(Serde.class), any())).thenReturn(grouped);
    when(sourceStream.groupByKey(any(Grouped.class))).thenReturn(groupedStream);
    when(sourceStream.filter(any())).thenReturn(filteredStream);
    when(filteredStream.groupBy(any(KeyValueMapper.class), any(Grouped.class)))
        .thenReturn(groupedStream);
    when(sourceStep.getProperties()).thenReturn(SOURCE_PROPERTIES);
    when(sourceStep.build(any())).thenReturn(
        new KStreamHolder<>(sourceStream, SCHEMA, mock(KeySerdeFactory.class)));
    when(processingLogContext.getLoggerFactory()).thenReturn(processingLoggerFactory);
    when(processingLoggerFactory.getLogger(any())).thenReturn(mock(ProcessingLogger.class));
    streamGroupBy = new StreamGroupBy<>(
        PROPERTIES,
        sourceStep,
        FORMATS,
        GROUP_BY_EXPRESSIONS
    );
    planBuilder = new KSPlanBuilder(
        queryBuilder,
        mock(SqlPredicateFactory.class),
        mock(AggregateParamsFactory.class),
        new StreamsFactories(
            groupedFactory,
            mock(JoinedFactory.class),
            mock(MaterializedFactory.class),
            mock(StreamJoinedFactory.class),
            mock(ConsumedFactory.class)
        )
    );
    streamGroupByKey = new StreamGroupByKey(PROPERTIES, sourceStep, FORMATS);
  }

  @Test
  public void shouldPerformGroupByCorrectly() {
    // When:
    final KGroupedStreamHolder result = streamGroupBy.build(planBuilder);

    // Then:
    assertThat(result.getGroupedStream(), is(groupedStream));
    verify(sourceStream).filter(any());
    verify(filteredStream).groupBy(any(), same(grouped));
    verifyNoMoreInteractions(filteredStream, sourceStream);
  }

  @Test
  public void shouldFilterNullRowsBeforeGroupBy() {
    // When:
    streamGroupBy.build(planBuilder);

    // Then:
    verify(sourceStream).filter(predicateCaptor.capture());
    final Predicate<Struct, GenericRow> predicate = predicateCaptor.getValue();
    assertThat(predicate.test(STRING_KEY_BUILDER.build("foo"), new GenericRow()), is(true));
    assertThat(predicate.test(STRING_KEY_BUILDER.build("foo"), null), is(false));
  }

  @Test
  public void shouldBuildGroupedCorrectlyForGroupBy() {
    // When:
    streamGroupBy.build(planBuilder);

    // Then:
    verify(groupedFactory).create("foo-groupby", keySerde, valueSerde);
  }

  @Test
  public void shouldReturnCorrectSchemaForGroupBy() {
    // When:
    final KGroupedStreamHolder result = streamGroupBy.build(planBuilder);

    // Then:
    assertThat(result.getSchema(), is(LogicalSchema.builder()
        .withRowTime()
        .keyColumn(SchemaUtil.ROWKEY_NAME, SqlTypes.STRING)
        .valueColumns(SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldBuildKeySerdeCorrectlyForGroupBy() {
    // When:
    streamGroupBy.build(planBuilder);

    // Then:
    verify(queryBuilder).buildKeySerde(
        FORMATS.getKeyFormat(),
        REKEYED_PHYSICAL_SCHEMA,
        STEP_CTX
    );
  }

  @Test
  public void shouldBuildValueSerdeCorrectlyForGroupBy() {
    // When:
    streamGroupBy.build(planBuilder);

    // Then:
    verify(queryBuilder).buildValueSerde(
        FORMATS.getValueFormat(),
        REKEYED_PHYSICAL_SCHEMA,
        STEP_CTX
    );
  }

  @Test
  public void shouldReturnCorrectSchemaForGroupByKey() {
    // When:
    final KGroupedStreamHolder result = streamGroupByKey.build(planBuilder);

    // Then:
    assertThat(result.getSchema(), is(SCHEMA));
  }

  @Test
  public void shouldPerformGroupByKeyCorrectly() {
    // When:
    final KGroupedStreamHolder result = streamGroupByKey.build(planBuilder);

    // Then:
    assertThat(result.getGroupedStream(), is(groupedStream));
    verify(sourceStream).groupByKey(grouped);
    verifyNoMoreInteractions(sourceStream);
  }

  @Test
  public void shouldBuildGroupedCorrectlyForGroupByKey() {
    // When:
    streamGroupByKey.build(planBuilder);

    // Then:
    verify(groupedFactory).create("foo-groupby", keySerde, valueSerde);
  }

  @Test
  public void shouldBuildKeySerdeCorrectlyForGroupByKey() {
    // When:
    streamGroupByKey.build(planBuilder);

    // Then:
    verify(queryBuilder).buildKeySerde(
        FORMATS.getKeyFormat(),
        PHYSICAL_SCHEMA,
        STEP_CTX);
  }

  @Test
  public void shouldBuildValueSerdeCorrectlyForGroupByKey() {
    // When:
    streamGroupByKey.build(planBuilder);

    // Then:
    verify(queryBuilder).buildValueSerde(
        FORMATS.getValueFormat(),
        PHYSICAL_SCHEMA,
        STEP_CTX
    );
  }

  private static Expression columnReference(final String column) {
    return new UnqualifiedColumnReferenceExp(ColumnName.of(column));
  }
}