package com.ontos.module.query.core.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.framework.common.util.json.JsonUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_UNKNOWN_FIELD;

/**
 * DSL 入口解码器：未知字段 fail-closed（M2/E5、ADR-0003）。
 *
 * <p>通用 JSON 工具（抄自 yudao）为兼容外部报文关闭了 FAIL_ON_UNKNOWN_PROPERTIES，
 * 不能改；因此 DSL 入口在反序列化前按契约做白名单校验，嵌套对象逐层校验。
 * 拒绝额外字段可阻止 LLM 夹带自由 SQL、物理列名或伪造的服务端回填字段。
 *
 * <p>字段名遵循现有 DSL 的 camelCase 契约（与前端 service/query 一致）；
 * analysis-mode 规格中的 snake_case 是概念示意，接入时按本白名单映射。
 * timePatternId / timeParserVersion / timeRange.source 由服务端回填，客户端提交一律拒绝。
 */
@Component
public class DslCodec {

    private static final Set<String> TOP_LEVEL = Set.of(
            "manifestId", "scenarioId", "mode", "metricId", "dimensions", "filters",
            "timeRange", "timeExpressionRaw", "explicitRange", "subIntents", "analysisParams",
            "entityId", "attributes", "identity", "identities", "identityPropertyId",
            "booleanMetricId",
            "limit", "variantHint");
    private static final Set<String> TIME_RANGE = Set.of("field", "from", "to");
    private static final Set<String> FILTER = Set.of("fieldId", "operator", "values");
    private static final Set<String> IDENTITY = Set.of("propertyId", "value");
    private static final Set<String> ANALYSIS_PARAMS = Set.of(
            "steps", "cohortWindow", "stepWindow", "subjectPropertyId",
            "deduplication", "conversionDenominator", "period");
    private static final Set<String> COHORT_WINDOW = Set.of("timeExpressionRaw");
    private static final Set<String> SUB_INTENT = Set.of(
            "subIntentId", "metricId", "mode", "dimensions", "filters",
            "timeExpressionRaw", "dependsOn");

    /** 把请求体严格解码为类型化 DSL；任何未知字段/结构错误都在进入编译链路前拒绝。 */
    public QueryDsl decode(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw exception(DSL_INVALID, "请求体必须是 DSL 对象");
        }
        String json = body.toString();
        Map<String, Object> root = asMap(JsonUtils.parseMap(json), "dsl");
        checkKeys(root, TOP_LEVEL, "dsl");

        checkObject(root.get("timeRange"), TIME_RANGE, "timeRange");
        checkObject(root.get("explicitRange"), TIME_RANGE, "explicitRange");
        checkObject(root.get("identity"), IDENTITY, "identity");
        checkList(root.get("filters"), FILTER, "filters");

        Map<String, Object> params = root.containsKey("analysisParams")
                ? asMap(root.get("analysisParams"), "analysisParams") : null;
        if (params != null) {
            checkKeys(params, ANALYSIS_PARAMS, "analysisParams");
            if (params.containsKey("cohortWindow")) {
                checkObject(params.get("cohortWindow"), COHORT_WINDOW, "analysisParams.cohortWindow");
            }
        }

        if (root.containsKey("subIntents")) {
            List<Map<String, Object>> subs = asMapList(root.get("subIntents"), "subIntents");
            for (Map<String, Object> sub : subs) {
                checkKeys(sub, SUB_INTENT, "subIntents[]");
                if (sub.containsKey("filters")) {
                    checkList(sub.get("filters"), FILTER, "subIntents[].filters");
                }
            }
        }

        QueryDsl dsl;
        try {
            dsl = JsonUtils.parseObject(json, QueryDsl.class);
        } catch (IllegalArgumentException ex) {
            // JsonUtils.parseObject 把 JacksonException 包成 IllegalArgumentException；
            // 不转换会冒到 Spring 变成 500，破坏「畸形 DSL 一律以业务错误 fail-closed」的契约。
            throw exception(DSL_INVALID, "DSL 字段类型不合法: " + rootMessage(ex));
        }
        if (dsl == null) {
            throw exception(DSL_INVALID, "DSL 反序列化结果为空");
        }
        return dsl;
    }

    private static void checkKeys(Map<String, Object> map, Set<String> allowed, String path) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw exception(DSL_UNKNOWN_FIELD, path + "." + key);
            }
        }
    }

    private static void checkObject(Object value, Set<String> allowed, String path) {
        if (value == null) {
            return;
        }
        checkKeys(asMap(value, path), allowed, path);
    }

    private static void checkList(Object value, Set<String> allowed, String path) {
        if (value == null) {
            return;
        }
        for (Map<String, Object> item : asMapList(value, path)) {
            checkKeys(item, allowed, path + "[]");
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? "unknown" : (message.length() > 200 ? message.substring(0, 200) : message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw exception(DSL_INVALID, path + " 必须是对象");
        }
        return (Map<String, Object>) map;
    }

    private static List<Map<String, Object>> asMapList(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw exception(DSL_INVALID, path + " 必须是数组");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            result.add(asMap(item, path + "[]"));
        }
        return result;
    }

}
