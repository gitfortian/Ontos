package com.ontos.module.query.core.dsl;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.common.util.json.JsonUtils;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_UNKNOWN_FIELD;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** M2/E5：DSL 入口未知字段 fail-closed（ADR-0003）。 */
class DslCodecTest {

    private final DslCodec codec = new DslCodec();

    private static JsonNode json(String text) {
        return JsonUtils.parseTree(text);
    }

    private static String base() {
        return "{\"manifestId\":\"manifest-001\",\"metricId\":\"gmv\","
                + "\"mode\":\"QUERY\",\"dimensions\":[\"region\"],\"limit\":100}";
    }

    private void assertRejected(String body, Integer expectedCode) {
        ServiceException ex = assertThrows(ServiceException.class, () -> codec.decode(json(body)));
        assertEquals(expectedCode, ex.getCode());
    }

    @Test
    void shouldDecodeValidDsl() {
        QueryDsl dsl = codec.decode(json(base()));
        assertEquals("gmv", dsl.getMetricId());
        assertEquals(QueryDsl.AnalysisMode.QUERY, dsl.getMode());
    }

    @Test
    void shouldDecodeFullCamelCaseContract() {
        String body = "{\"manifestId\":\"manifest-001\",\"metricId\":\"gmv\",\"mode\":\"FUNNEL\","
                + "\"timeRange\":{\"field\":\"order_date\"},\"timeExpressionRaw\":\"近 7 天\","
                + "\"filters\":[{\"fieldId\":\"region\",\"operator\":\"EQ\",\"values\":[\"华东\"]}],"
                + "\"analysisParams\":{\"steps\":[\"ORDER_PLACED\",\"REFUND\"],"
                + "\"cohortWindow\":{\"timeExpressionRaw\":\"近 30 天\"},\"stepWindow\":\"7d\","
                + "\"subjectPropertyId\":\"property.customer.customer_id\","
                + "\"deduplication\":\"first_occurrence\",\"conversionDenominator\":\"previous_step\"},"
                + "\"subIntents\":[{\"subIntentId\":\"s1\",\"metricId\":\"gmv\",\"dependsOn\":[]}]}";
        QueryDsl dsl = assertDoesNotThrow(() -> codec.decode(json(body)));
        assertEquals(2, dsl.getAnalysisParams().getSteps().size());
        assertEquals("7d", dsl.getAnalysisParams().getStepWindow());
        assertEquals("s1", dsl.getSubIntents().get(0).getSubIntentId());
    }

    @Test
    void shouldRejectUnknownTopLevelField() {
        assertRejected("{\"metricId\":\"gmv\",\"freeSql\":\"select * from orders\"}", DSL_UNKNOWN_FIELD.getCode());
    }

    @Test
    void shouldRejectUnknownNestedFilterField() {
        assertRejected("{\"metricId\":\"gmv\",\"filters\":[{\"fieldId\":\"region\","
                + "\"physicalColumn\":\"orders.region\"}]}", DSL_UNKNOWN_FIELD.getCode());
    }

    @Test
    void shouldRejectClientSuppliedTimeSource() {
        assertRejected("{\"metricId\":\"gmv\",\"timeRange\":{\"field\":\"order_date\","
                + "\"source\":\"EXPRESSION\",\"from\":\"2026-01-01T00:00:00Z\","
                + "\"to\":\"2026-02-01T00:00:00Z\"}}", DSL_UNKNOWN_FIELD.getCode());
    }

    @Test
    void shouldRejectClientSuppliedParserVersion() {
        assertRejected("{\"metricId\":\"gmv\",\"timeExpressionRaw\":\"近 3 天\","
                + "\"timeParserVersion\":\"forged-1.0\"}", DSL_UNKNOWN_FIELD.getCode());
    }

    @Test
    void shouldRejectUnknownAnalysisParamsField() {
        assertRejected("{\"metricId\":\"gmv\",\"mode\":\"FUNNEL\","
                + "\"analysisParams\":{\"steps\":[],\"sql\":\"select 1\"}}", DSL_UNKNOWN_FIELD.getCode());
    }

    @Test
    void shouldRejectUnknownSubIntentField() {
        assertRejected("{\"metricId\":\"gmv\","
                + "\"subIntents\":[{\"subIntentId\":\"s1\",\"metricId\":\"gmv\",\"sqlText\":\"...\"}]}",
                DSL_UNKNOWN_FIELD.getCode());
    }

    @Test
    void shouldRejectNonObjectBody() {
        assertRejected("[\"gmv\"]", DSL_INVALID.getCode());
    }

    @Test
    void shouldRejectMalformedStructure() {
        assertRejected("{\"metricId\":\"gmv\",\"timeRange\":\"上个月\"}", DSL_INVALID.getCode());
    }

    @Test
    void shouldDecodeCaliberVariantId() {
        QueryDsl dsl = codec.decode(json(
                "{\"manifestId\":\"m\",\"metricId\":\"gmv\",\"mode\":\"QUERY\","
                        + "\"caliberVariantId\":\"metric.gmv.online\"}"));
        assertEquals("metric.gmv.online", dsl.getCaliberVariantId());
    }

    @Test
    void shouldConvertJacksonTypeErrorsToBusinessRejection() {
        // JsonUtils.parseObject 把 JacksonException 包成 IllegalArgumentException；
        // 不转换会冒到 Spring 变 500，破坏「畸形 DSL 一律业务错误 fail-closed」契约。
        assertRejected("{\"metricId\":\"gmv\",\"dimensions\":[{\"x\":1}]}", DSL_INVALID.getCode());
        assertRejected("{\"metricId\":\"gmv\",\"limit\":\"not-a-number\"}", DSL_INVALID.getCode());
        assertRejected("{\"metricId\":\"gmv\",\"mode\":\"NOT_A_MODE\"}", DSL_INVALID.getCode());
        assertRejected("{\"metricId\":\"gmv\",\"filters\":[{\"fieldId\":\"region\","
                + "\"operator\":\"EQ\",\"values\":[{\"x\":1}]}]}", DSL_INVALID.getCode());
    }

    @Test
    void shouldDecodeSubIntentTimeRange() {
        // 子意图必须能带时间维度：否则相对时间解析拿不到 field，复合请求整体不可用
        QueryDsl dsl = codec.decode(json("""
                {"manifestId":"manifest-001","mode":"QUERY","subIntents":[
                  {"subIntentId":"s1","metricId":"gmv","dimensions":["region"],
                   "timeExpressionRaw":"近 30 天","timeRange":{"field":"order_date"}}]}
                """));

        assertEquals("order_date", dsl.getSubIntents().get(0).getTimeRange().getField());
        assertEquals("近 30 天", dsl.getSubIntents().get(0).getTimeExpressionRaw());
    }

    @Test
    void shouldRejectClientSuppliedSourceInsideSubIntentTimeRange() {
        // source 是服务端回填字段，客户端提交一律拒绝（与顶层 timeRange 同一条边界）
        assertRejected("{\"manifestId\":\"manifest-001\",\"mode\":\"QUERY\",\"subIntents\":["
                + "{\"subIntentId\":\"s1\",\"metricId\":\"gmv\",\"timeRange\":"
                + "{\"field\":\"order_date\",\"source\":\"EXPRESSION\"}}]}",
                DSL_UNKNOWN_FIELD.getCode());
    }

}
