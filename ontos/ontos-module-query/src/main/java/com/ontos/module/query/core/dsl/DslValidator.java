package com.ontos.module.query.core.dsl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.query.core.QueryProperties;
import org.springframework.stereotype.Component;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.ASSET_MODE_NOT_ALLOWED;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.DIMENSION_NOT_ALLOWED;
import static com.ontos.module.query.enums.ErrorCodeConstants.ABSOLUTE_DATE_REJECTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.ANALYSIS_MODE_NOT_IMPLEMENTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.CROSS_TABLE_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_ATTRIBUTE_NOT_PROJECTABLE;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_BATCH_LIMIT_EXCEEDED;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_CROSS_TABLE_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_IDENTITY_REQUIRED;
import static com.ontos.module.query.enums.ErrorCodeConstants.SENSITIVE_BULK_EXPORT_REJECTED;

/**
 * DSL 校验器：只做结构与授权校验，不执行查询。
 *
 * <p>校验顺序固定：模式参数 → 子意图 → 指标 → 分析模式 → 维度 → 过滤 → 时间窗 → 行数/敏感闸门。
 * 任一步失败即整单拒绝，不返回部分结果。
 *
 * <p>M2/W2.5：补上 analysis_params（T3.0 规格）、sub_intents 结构、时间来源二通道、
 * 以及敏感资产批量导出的编译侧 guardrail（完整累计检测仍归 M4）。
 */
@Component
public class DslValidator {

    private static final Pattern STEP_WINDOW = Pattern.compile("^[1-9]\\d*[dhm]$");
    private static final Set<String> YOY_MOM_PERIODS = Set.of("MONTH", "YEAR");

    private final QueryProperties properties;

    public DslValidator(QueryProperties properties) {
        this.properties = properties;
    }

    public void validate(QueryDsl dsl, PublishedManifest manifest) {
        if (dsl == null) {
            throw invalidParamException("查询 DSL 不能为空");
        }
        QueryDsl.AnalysisMode mode = dsl.getMode() == null ? QueryDsl.AnalysisMode.QUERY : dsl.getMode();
        // W3/T3.2：lookup 系列以实体为入口，不引用 metric（v1.4 §6.3.1 允许 metrics 为空），
        // 必须在 metric 必填检查之前分流。
        if (mode == QueryDsl.AnalysisMode.LOOKUP || mode == QueryDsl.AnalysisMode.LOOKUP_BATCH) {
            validateLookup(dsl, manifest, mode);
            return;
        }
        if (dsl.getMetricId() == null || dsl.getMetricId().isBlank()) {
            throw exception(DSL_INVALID, "metricId 不能为空");
        }
        SemanticManifest semantic = manifest.getSemantic();
        SemanticManifest.Metric metric = semantic.requireMetric(dsl.getMetricId());

        validateAnalysisParams(dsl, mode);
        validateSubIntents(dsl, semantic);

        if (!metric.getAllowedModes().contains(mode.name())) {
            throw exception(ASSET_MODE_NOT_ALLOWED, mode.name());
        }
        // fail-closed（PoC-04 缺陷修复）：只有已落地确定性构造的模式才放行。
        // W3/T3.2 已实现 QUERY / LOOKUP / LOOKUP_BATCH（lookup 在入口分流），
        // W3/T3.3 实现 YOY_MOM；FUNNEL 因清单缺事件判定条件暂缓（R-12），其余模式一律拒绝。
        if (mode != QueryDsl.AnalysisMode.QUERY && mode != QueryDsl.AnalysisMode.YOY_MOM) {
            throw exception(ANALYSIS_MODE_NOT_IMPLEMENTED, mode.name());
        }
        if (mode == QueryDsl.AnalysisMode.YOY_MOM && dsl.getTimeRange() == null) {
            throw exception(DSL_INVALID, "yoy_mom 需要 timeRange 才能确定本期与对比期");
        }

        List<String> dimensionIds = dsl.getDimensions() == null ? List.of() : dsl.getDimensions();
        List<SemanticManifest.Dimension> resolvedDimensions = new ArrayList<>();
        for (String dimensionId : dimensionIds) {
            SemanticManifest.Dimension dimension = semantic.requireDimension(dimensionId);
            resolvedDimensions.add(dimension);
            requireAllowedDimension(metric, dimensionId);
            if (!Objects.equals(dimension.getTable(), metric.getTable())
                    && !joinCovers(dsl, dimension.getTable())) {
                throw exception(CROSS_TABLE_UNSUPPORTED, dimensionId + " -> " + metric.getTable());
            }
        }

        List<QueryDsl.Filter> filters = dsl.getFilters() == null ? List.of() : dsl.getFilters();
        for (QueryDsl.Filter filter : filters) {
            // ADR-0003 判据 6：维度白名单对「分组 / 过滤 / 时间」三条引用路径一体生效。
            // T3.1 放开跨表 Join 后，若只在分组处校验，过滤条件即可绕过指标级授权读到未授权表。
            requireAllowedDimension(metric, filter.getFieldId());
            SemanticManifest.Dimension dimension = semantic.requireDimension(filter.getFieldId());
            if (!Objects.equals(dimension.getTable(), metric.getTable())
                    && !joinCovers(dsl, dimension.getTable())) {
                throw exception(CROSS_TABLE_UNSUPPORTED, filter.getFieldId() + " -> " + metric.getTable());
            }
            validateFilterValues(filter);
        }

        validateTimeRange(dsl, semantic, metric);

        int limit = dsl.getLimit() == null ? properties.getMaxRows() : dsl.getLimit();
        if (limit < 1 || limit > properties.getMaxRows()) {
            throw exception(DSL_INVALID, "limit 必须在 [1, " + properties.getMaxRows() + "]");
        }
        dsl.setLimit(limit);
        boolean sensitive = metric.isSensitive()
                || resolvedDimensions.stream().anyMatch(SemanticManifest.Dimension::isSensitive);
        if (sensitive && dimensionIds.isEmpty() && limit > properties.getSensitiveUngroupedLimit()) {
            throw exception(SENSITIVE_BULK_EXPORT_REJECTED,
                    metric.getId() + " 无维度分组且 limit=" + limit);
        }
    }

    /**
     * 时间窗来源二通道（M2/T2.2 + W2.5）：
     * EXPRESSION 必须带服务端解析版本与原始表达；EXPLICIT 必须来自 explicitRange 且值一致。
     * 客户端直接塞 from/to 而无合法来源标记 = 伪造绝对日期，一律拒绝。
     */
    private void validateTimeRange(QueryDsl dsl, SemanticManifest semantic, SemanticManifest.Metric metric) {
        QueryDsl.TimeRange range = dsl.getTimeRange();
        if (range == null) {
            return;
        }
        String source = range.getSource();
        if (QueryDsl.TimeRange.SOURCE_EXPRESSION.equals(source)) {
            if (isBlank(dsl.getTimeExpressionRaw())) {
                throw exception(ABSOLUTE_DATE_REJECTED,
                        "source=EXPRESSION 但缺少 time_expression_raw：解析器产出不可伪造");
            }
            if (isBlank(dsl.getTimePatternId()) || isBlank(dsl.getTimeParserVersion())) {
                throw exception(ABSOLUTE_DATE_REJECTED,
                        "source=EXPRESSION 缺少服务端解析产物（patternId / parserVersion）");
            }
        } else if (QueryDsl.TimeRange.SOURCE_EXPLICIT.equals(source)) {
            QueryDsl.TimeRange explicit = dsl.getExplicitRange();
            if (!isBlank(dsl.getTimeExpressionRaw()) || explicit == null
                    || !Objects.equals(range.getFrom(), explicit.getFrom())
                    || !Objects.equals(range.getTo(), explicit.getTo())
                    || !Objects.equals(range.getField(), explicit.getField())) {
                throw exception(ABSOLUTE_DATE_REJECTED, "显式日期必须来自 explicitRange 且与 timeRange 一致");
            }
        } else {
            throw exception(ABSOLUTE_DATE_REJECTED,
                    "timeRange 缺少合法来源标记：相对时间请走 timeExpressionRaw，绝对日期仅限 explicitRange");
        }

        if (range.getFrom() == null || range.getTo() == null) {
            throw exception(DSL_INVALID, "timeRange 需要同时提供 from 与 to");
        }
        if (range.getFrom().isAfter(range.getTo())) {
            throw exception(DSL_INVALID, "timeRange 起点晚于终点");
        }
        if (isBlank(range.getField())) {
            throw exception(DSL_INVALID, "timeRange 缺少 field");
        }
        requireAllowedDimension(metric, range.getField());
        SemanticManifest.Dimension dimension = semantic.requireDimension(range.getField());
        if (!Objects.equals(dimension.getTable(), metric.getTable())
                && !joinCovers(dsl, dimension.getTable())) {
            throw exception(CROSS_TABLE_UNSUPPORTED, range.getField() + " -> " + metric.getTable());
        }
    }

    /** 指标级维度授权：分组、过滤、时间窗三条引用路径共用（ADR-0003 判据 6）。 */
    private static void requireAllowedDimension(SemanticManifest.Metric metric, String dimensionId) {
        if (!metric.getAllowedDimensions().contains(dimensionId)) {
            throw exception(DIMENSION_NOT_ALLOWED, dimensionId);
        }
    }

    /** W3/T3.1：跨表字段只有被服务端 UNIQUE 路径覆盖时才放行。 */
    private static boolean joinCovers(QueryDsl dsl, String table) {
        List<QueryDsl.ResolvedJoin> joins = dsl.getResolvedJoins();
        if (joins == null || table == null) {
            return false;
        }
        for (QueryDsl.ResolvedJoin join : joins) {
            if (table.equals(join.getTable())
                    || columnBelongsTo(join.getLeftColumn(), table)
                    || columnBelongsTo(join.getRightColumn(), table)) {
                return true;
            }
        }
        return false;
    }

    private static boolean columnBelongsTo(String column, String table) {
        if (column == null) {
            return false;
        }
        int lastDot = column.lastIndexOf('.');
        return lastDot > 0 && table.equals(column.substring(0, lastDot));
    }

    /**
     * lookup / lookup_batch 校验（v1.4 §6.3.1、M2/T3.2）。
     *
     * <p>四条硬边界：实体必须已发布；投影属性必须属于实体、可投影且同表（M2 单表定档）；
     * 标识属性必须属于实体且可定位；批量上限与 limit 取配置值，不信任客户端。
     * 时间窗 / 分析参数 / 子意图不属于实体直查，出现即拒绝。
     */
    private void validateLookup(QueryDsl dsl, PublishedManifest manifest, QueryDsl.AnalysisMode mode) {
        OntologySnapshot ontology = manifest.getOntology();
        String entityId = dsl.getEntityId();
        if (isBlank(entityId) || !ontology.conceptIndex().containsKey(entityId)) {
            throw exception(LOOKUP_IDENTITY_REQUIRED, "entityId=" + entityId);
        }
        if (!isBlank(dsl.getMetricId())
                || (dsl.getDimensions() != null && !dsl.getDimensions().isEmpty())) {
            throw exception(DSL_INVALID, "lookup 不接受 metricId / dimensions");
        }
        if (dsl.getAnalysisParams() != null
                || (dsl.getSubIntents() != null && !dsl.getSubIntents().isEmpty())) {
            throw exception(DSL_INVALID, "lookup 不接受 analysisParams / subIntents");
        }
        if (dsl.getTimeRange() != null || dsl.getExplicitRange() != null
                || !isBlank(dsl.getTimeExpressionRaw())) {
            throw exception(DSL_INVALID, "lookup 是实体直查，不接受时间窗");
        }

        List<String> attributes = dsl.getAttributes() == null ? List.of() : dsl.getAttributes();
        if (attributes.isEmpty()) {
            throw exception(LOOKUP_ATTRIBUTE_NOT_PROJECTABLE, "attributes 为空");
        }
        Set<String> tables = new HashSet<>();
        for (String attributeId : attributes) {
            String table = tableOf(requireLookupProperty(ontology, entityId, attributeId, true).getColumn());
            if (table == null) {
                throw exception(LOOKUP_CROSS_TABLE_UNSUPPORTED, "属性缺少表限定: " + attributeId);
            }
            tables.add(table);
        }
        if (tables.size() > 1) {
            throw exception(LOOKUP_CROSS_TABLE_UNSUPPORTED, String.join(",", tables));
        }

        for (QueryDsl.Filter filter : dsl.getFilters() == null ? List.<QueryDsl.Filter>of() : dsl.getFilters()) {
            OntologySnapshot.Property property = requireLookupProperty(ontology, entityId, filter.getFieldId(), false);
            if (!property.isFilterable()) {
                throw exception(LOOKUP_ATTRIBUTE_NOT_PROJECTABLE, "过滤属性不可过滤: " + filter.getFieldId());
            }
            validateFilterValues(filter);
        }

        if (mode == QueryDsl.AnalysisMode.LOOKUP) {
            if (dsl.getIdentity() == null || isBlank(dsl.getIdentity().getPropertyId())
                    || isBlank(dsl.getIdentity().getValue())) {
                throw exception(LOOKUP_IDENTITY_REQUIRED, "identity.propertyId / value");
            }
            requireIdentityProperty(ontology, entityId, dsl.getIdentity().getPropertyId());
            if (dsl.getIdentities() != null && !dsl.getIdentities().isEmpty()) {
                throw exception(DSL_INVALID, "lookup 单实体不接受 identities");
            }
            int limit = dsl.getLimit() == null ? 1 : dsl.getLimit();
            if (limit != 1) {
                throw exception(DSL_INVALID, "lookup 单实体 limit 必须为 1");
            }
            dsl.setLimit(1);
            return;
        }

        if (isBlank(dsl.getIdentityPropertyId())) {
            throw exception(LOOKUP_IDENTITY_REQUIRED, "identityPropertyId");
        }
        requireIdentityProperty(ontology, entityId, dsl.getIdentityPropertyId());
        if (dsl.getIdentity() != null) {
            throw exception(DSL_INVALID, "lookup_batch 不接受 identity 单实体字段");
        }
        List<String> identities = dsl.getIdentities() == null ? List.of() : dsl.getIdentities();
        if (identities.isEmpty()) {
            throw exception(LOOKUP_IDENTITY_REQUIRED, "identities 为空");
        }
        if (identities.size() > properties.getMaxBatchSize()) {
            throw exception(LOOKUP_BATCH_LIMIT_EXCEEDED,
                    identities.size() + " > " + properties.getMaxBatchSize());
        }
        for (String identity : identities) {
            if (isBlank(identity)) {
                throw exception(LOOKUP_IDENTITY_REQUIRED, "identities 含空值");
            }
        }
        int limit = dsl.getLimit() == null ? properties.getMaxBatchSize() : dsl.getLimit();
        if (limit < 1 || limit > properties.getMaxBatchSize()) {
            throw exception(LOOKUP_BATCH_LIMIT_EXCEEDED, "limit=" + limit);
        }
        // limit 小于标识数会静默截断（后面的 identity 被误判为不可用），必须拒绝
        if (limit < identities.size()) {
            throw exception(LOOKUP_BATCH_LIMIT_EXCEEDED,
                    "limit=" + limit + " < identities=" + identities.size());
        }
        dsl.setLimit(limit);
    }

    private static OntologySnapshot.Property requireLookupProperty(OntologySnapshot ontology, String entityId,
                                                                  String propertyId, boolean projectable) {
        OntologySnapshot.Property property = ontology.propertyIndex().get(propertyId);
        if (property == null || !entityId.equals(property.getConceptId())) {
            throw exception(LOOKUP_ATTRIBUTE_NOT_PROJECTABLE, propertyId);
        }
        if (projectable && !property.isLookupProjectable()) {
            throw exception(LOOKUP_ATTRIBUTE_NOT_PROJECTABLE, propertyId);
        }
        return property;
    }

    /** 标识属性必须可过滤；存在性敏感属性允许「可查不可看值」（v1.4 §8.2 存在性保护）。 */
    private static void requireIdentityProperty(OntologySnapshot ontology, String entityId, String propertyId) {
        OntologySnapshot.Property property = ontology.propertyIndex().get(propertyId);
        if (property == null || !entityId.equals(property.getConceptId())
                || (!property.isFilterable() && !property.isExistenceSensitive())) {
            throw exception(LOOKUP_IDENTITY_REQUIRED, propertyId);
        }
    }

    private static void validateFilterValues(QueryDsl.Filter filter) {
        if (filter.getOperator() == null) {
            throw exception(DSL_INVALID, "过滤条件缺少 operator");
        }
        List<String> values = filter.getValues() == null ? List.of() : filter.getValues();
        if (values.isEmpty()) {
            throw exception(DSL_INVALID, "过滤条件缺少 values: " + filter.getFieldId());
        }
        int expected = switch (filter.getOperator()) {
            case EQ, GTE, LTE -> 1;
            case BETWEEN -> 2;
            case IN, NOT_IN -> -1; // IN / NOT_IN 允许任意非空集合
        };
        if (expected > 0 && values.size() != expected) {
            throw exception(DSL_INVALID, "过滤条件 " + filter.getOperator()
                    + " 需要 " + expected + " 个值，实际 " + values.size() + ": " + filter.getFieldId());
        }
    }

    /** "table.column" / "schema.table.column" → 表限定名；裸列名返回 null。 */
    private static String tableOf(String column) {
        if (column == null) {
            return null;
        }
        int lastDot = column.lastIndexOf('.');
        return lastDot <= 0 ? null : column.substring(0, lastDot);
    }

    /** funnel / yoy_mom 的 analysis_params 契约（analysis-mode 规格 v1）。W3 构造器据此实现。 */
    private void validateAnalysisParams(QueryDsl dsl, QueryDsl.AnalysisMode mode) {
        QueryDsl.AnalysisParams params = dsl.getAnalysisParams();
        if (params == null) {
            return;
        }
        boolean funnel = mode == QueryDsl.AnalysisMode.FUNNEL;
        boolean yoyMom = mode == QueryDsl.AnalysisMode.YOY_MOM;
        if (!funnel && !yoyMom) {
            throw exception(DSL_INVALID, "analysisParams 仅允许 funnel / yoy_mom 模式");
        }
        if (funnel) {
            List<String> steps = params.getSteps() == null ? List.of() : params.getSteps();
            if (steps.size() < 2) {
                throw exception(DSL_INVALID, "funnel steps 至少需要 2 步（规格 v1 §1.1）");
            }
            if (new HashSet<>(steps).size() != steps.size()) {
                throw exception(DSL_INVALID, "funnel steps 不可重复");
            }
            if (params.getCohortWindow() == null || isBlank(params.getCohortWindow().getTimeExpressionRaw())) {
                throw exception(DSL_INVALID, "funnel 缺少 cohortWindow.timeExpressionRaw");
            }
            if (isBlank(params.getStepWindow()) || !STEP_WINDOW.matcher(params.getStepWindow()).matches()) {
                throw exception(DSL_INVALID, "funnel stepWindow 形如 7d / 12h");
            }
            if (isBlank(params.getSubjectPropertyId())) {
                throw exception(DSL_INVALID, "funnel 缺少 subjectPropertyId（本体属性 ID）");
            }
            if (!"first_occurrence".equals(params.getDeduplication())
                    || !"previous_step".equals(params.getConversionDenominator())) {
                throw exception(DSL_INVALID, "funnel 去重/分母口径须按规格 v1 定档");
            }
            if (!isBlank(params.getPeriod())) {
                throw exception(DSL_INVALID, "funnel 不接受 period");
            }
            return;
        }
        if (!YOY_MOM_PERIODS.contains(params.getPeriod())) {
            throw exception(DSL_INVALID, "yoy_mom period 仅允许 MONTH / YEAR");
        }
        if (params.getSteps() != null && !params.getSteps().isEmpty()) {
            throw exception(DSL_INVALID, "yoy_mom 不接受 steps");
        }
        if (params.getStepWindow() != null || params.getSubjectPropertyId() != null
                || params.getDeduplication() != null || params.getConversionDenominator() != null) {
            throw exception(DSL_INVALID, "yoy_mom 不接受 funnel 专属参数");
        }
    }

    /** 复合意图结构校验：M2 只支持无依赖子集，结果依赖类直接拒绝并转澄清（全链路归 M6）。 */
    private void validateSubIntents(QueryDsl dsl, SemanticManifest semantic) {
        List<QueryDsl.SubIntent> subIntents = dsl.getSubIntents();
        if (subIntents == null || subIntents.isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (QueryDsl.SubIntent sub : subIntents) {
            if (isBlank(sub.getSubIntentId()) || !ids.add(sub.getSubIntentId())) {
                throw exception(DSL_INVALID, "subIntents 的 subIntentId 必填且唯一");
            }
            if (isBlank(sub.getMetricId())) {
                throw exception(DSL_INVALID, "subIntents 缺少 metricId: " + sub.getSubIntentId());
            }
            semantic.requireMetric(sub.getMetricId());
            if (sub.getDependsOn() != null && !sub.getDependsOn().isEmpty()) {
                throw exception(DSL_INVALID, "M2 不支持结果依赖子意图，转澄清（全链路归 M6）");
            }
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

}
