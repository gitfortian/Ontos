package com.ontos.module.query.core.dsl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.ontos.module.ontology.core.ManifestPayload;
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
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_DIMENSION_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_EVENT_MAPPING_MISSING;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_EVENT_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_TIME_WINDOW_MISSING;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_ATTRIBUTE_NOT_PROJECTABLE;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_BATCH_LIMIT_EXCEEDED;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_CROSS_TABLE_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_IDENTITY_REQUIRED;
import static com.ontos.module.query.enums.ErrorCodeConstants.RULE_EXPRESSION_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.RULE_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.RULE_NOT_PUBLISHED;
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
    /** M2 规则求值支持的单值比较算子；IN（集合阈值）归 M6。 */
    private static final Set<String> RULE_OPERATORS = Set.of("EQ", "NEQ", "GT", "GTE", "LT", "LTE");

    private final QueryProperties properties;

    public DslValidator(QueryProperties properties) {
        this.properties = properties;
    }

    public void validate(QueryDsl dsl, PublishedManifest manifest) {
        if (dsl == null) {
            throw invalidParamException("查询 DSL 不能为空");
        }
        QueryDsl.AnalysisMode mode = modeOf(dsl);
        // W3/T3.2：lookup 系列以实体为入口，不引用 metric（v1.4 §6.3.1 允许 metrics 为空），
        // 必须在 metric 必填检查之前分流。
        if (mode == QueryDsl.AnalysisMode.LOOKUP || mode == QueryDsl.AnalysisMode.LOOKUP_BATCH) {
            validateLookup(dsl, manifest, mode);
            return;
        }
        // W3/T3.3a：funnel 以事件主体为入口，不使用 metric，时间窗在 cohortWindow
        if (mode == QueryDsl.AnalysisMode.FUNNEL) {
            validateFunnel(dsl, manifest);
            return;
        }
        rejectLookupOnlyFields(dsl);
        SemanticManifest semantic = manifest.getSemantic();
        SemanticManifest.Metric metric = validateMode(dsl, manifest);

        validateAnalysisParams(dsl, mode);
        validateSubIntents(dsl, semantic);

        if (mode == QueryDsl.AnalysisMode.YOY_MOM) {
            if (dsl.getTimeRange() == null) {
                throw exception(DSL_INVALID, "yoy_mom 需要 timeRange 才能确定本期与对比期");
            }
            // 时间维度由 period 承担；把它再放进分组会产出「按日展开、月值重复」的错位结果
            if (dsl.getDimensions() != null && dsl.getDimensions().contains(dsl.getTimeRange().getField())) {
                throw exception(DSL_INVALID, "yoy_mom 的分组维度不能是时间维度本身（时间口径由 period 承担）");
            }
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
     * 指标 + 模式授权的最小前置校验（R-18）。路径规划需要指标背后的物理表，
     * 规则型指标（无 table）若等到完整校验才拒绝，会先撞上 PATH_UNREACHABLE，
     * 把「模式不支持」误报成「路径不可达」。lookup / funnel 有独立入口，这里直接放行。
     */
    public SemanticManifest.Metric validateMode(QueryDsl dsl, PublishedManifest manifest) {
        QueryDsl.AnalysisMode mode = modeOf(dsl);
        if (mode == QueryDsl.AnalysisMode.LOOKUP || mode == QueryDsl.AnalysisMode.LOOKUP_BATCH
                || mode == QueryDsl.AnalysisMode.FUNNEL) {
            return null;
        }
        if (isBlank(dsl.getMetricId())) {
            throw exception(DSL_INVALID, "metricId 不能为空");
        }
        SemanticManifest.Metric metric = manifest.getSemantic().requireMetric(dsl.getMetricId());
        if (!metric.getAllowedModes().contains(mode.name())) {
            throw exception(ASSET_MODE_NOT_ALLOWED, dsl.getMetricId() + " / " + mode.name());
        }
        // fail-closed（PoC-04 缺陷修复）：只有已落地确定性构造的模式才放行。
        // QUERY / YOY_MOM 在本类编译；LOOKUP / LOOKUP_BATCH / FUNNEL 在入口分流；
        // ATTRIBUTION / RETENTION / DISCOVERY 一律拒绝。
        if (mode != QueryDsl.AnalysisMode.QUERY && mode != QueryDsl.AnalysisMode.YOY_MOM) {
            throw exception(ANALYSIS_MODE_NOT_IMPLEMENTED, mode.name());
        }
        return metric;
    }

    private static QueryDsl.AnalysisMode modeOf(QueryDsl dsl) {
        return dsl.getMode() == null ? QueryDsl.AnalysisMode.QUERY : dsl.getMode();
    }

    /**
     * 聚合模式不接受 lookup 专属字段。历史实现是静默忽略——用户（或 LLM）给出的
     * attributes / identity 会凭空消失，返回一个看似成功的聚合结果，属于「静默丢语义」。
     */
    private static void rejectLookupOnlyFields(QueryDsl dsl) {
        boolean hasLookupField = !isBlank(dsl.getEntityId())
                || dsl.getIdentity() != null
                || (dsl.getIdentities() != null && !dsl.getIdentities().isEmpty())
                || (dsl.getAttributes() != null && !dsl.getAttributes().isEmpty())
                || !isBlank(dsl.getIdentityPropertyId())
                || !isBlank(dsl.getBooleanMetricId());
        if (hasLookupField) {
            throw exception(DSL_INVALID,
                    "QUERY / YOY_MOM 不接受 lookup 字段（entityId / attributes / identity / identities /"
                            + " identityPropertyId / booleanMetricId）");
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

    /**
     * funnel 校验（analysis-mode 规格 v1 §1、ADR-0012）。
     *
     * <p>关键前置：每个 step 必须能匹配到**唯一**事件定义，且该事件声明了
     * eventTypeProperty / participantProperty / timeProperty 三件物理映射；
     * 缺任一即拒绝，绝不从 dedupKey 或命名习惯推断。M2 的维度只允许主体实体属性
     * （首次触点取用户属性），跨实体维度直接拒绝。
     */
    private void validateFunnel(QueryDsl dsl, PublishedManifest manifest) {
        OntologySnapshot ontology = manifest.getOntology();
        SemanticManifest semantic = manifest.getSemantic();
        if (!isBlank(dsl.getMetricId())) {
            throw exception(DSL_INVALID, "funnel 不接受 metricId（按事件主体计数）");
        }
        if (dsl.getTimeRange() != null || dsl.getExplicitRange() != null
                || !isBlank(dsl.getTimeExpressionRaw())) {
            throw exception(DSL_INVALID, "funnel 的时间窗只能走 analysisParams.cohortWindow");
        }
        if (dsl.getSubIntents() != null && !dsl.getSubIntents().isEmpty()) {
            throw exception(DSL_INVALID, "funnel 不接受 subIntents");
        }
        if (dsl.getFilters() != null && !dsl.getFilters().isEmpty()) {
            // 编译器的步进 CTE 没有接 filters 的位置；静默忽略会让「华东漏斗」返回全国数字。
            // M2 未给 funnel 过滤定档，按 fail-closed 明确拒绝，不用「看起来成功」糊过去。
            throw exception(DSL_INVALID, "funnel 暂不支持 filters（M2 未定档，拒绝而非静默忽略）");
        }
        if (!isBlank(dsl.getEntityId()) || dsl.getIdentity() != null
                || (dsl.getIdentities() != null && !dsl.getIdentities().isEmpty())
                || (dsl.getAttributes() != null && !dsl.getAttributes().isEmpty())
                || !isBlank(dsl.getIdentityPropertyId()) || !isBlank(dsl.getBooleanMetricId())) {
            throw exception(DSL_INVALID, "funnel 不接受 lookup 字段");
        }
        QueryDsl.AnalysisParams params = dsl.getAnalysisParams();
        if (params == null) {
            throw exception(DSL_INVALID, "funnel 缺少 analysisParams");
        }
        validateAnalysisParams(dsl, QueryDsl.AnalysisMode.FUNNEL);
        if (params.getCohortWindow().getFrom() == null || params.getCohortWindow().getTo() == null) {
            throw exception(FUNNEL_TIME_WINDOW_MISSING, "cohortWindow 尚未解析");
        }

        OntologySnapshot.Property subject = ontology.propertyIndex().get(params.getSubjectPropertyId());
        if (subject == null) {
            throw exception(DSL_INVALID, "subjectPropertyId 未发布: " + params.getSubjectPropertyId());
        }
        String subjectTable = tableOf(subject.getColumn());
        // 编译器以「首个步骤事件」的物理映射为整条 CTE 的判定列/时间列/主体列。
        // 若后续事件映射到别的属性或表，编译结果会静默按首事件解读后续步骤——
        // 那是「看起来成功、数字全错」，必须在入口拒绝，而不是留给 SQL 去猜。
        OntologySnapshot.EventDef baseline = null;
        for (String step : params.getSteps()) {
            List<OntologySnapshot.EventDef> matched = ontology.getEvents().stream()
                    .filter(event -> step.equals(event.getEventType()))
                    .toList();
            if (matched.size() != 1) {
                throw exception(FUNNEL_EVENT_NOT_FOUND, step + " 命中 " + matched.size() + " 个事件定义");
            }
            OntologySnapshot.EventDef event = matched.get(0);
            if (isBlank(event.getEventTypeProperty()) || isBlank(event.getParticipantProperty())
                    || isBlank(event.getTimeProperty())) {
                throw exception(FUNNEL_EVENT_MAPPING_MISSING, event.getId());
            }
            if (!subject.getConceptId().equals(event.getParticipantClass())) {
                throw exception(FUNNEL_EVENT_MAPPING_MISSING,
                        "participantClass 与主体不一致: " + event.getId());
            }
            if (baseline == null) {
                baseline = event;
            } else if (!samePhysicalMapping(baseline, event)) {
                throw exception(FUNNEL_EVENT_MAPPING_MISSING,
                        "步骤事件物理映射不一致（判定列/时间列/主体列必须相同）: " + event.getId());
            }
        }

        boolean sensitiveDimension = false;
        for (String dimensionId : dsl.getDimensions() == null ? List.<String>of() : dsl.getDimensions()) {
            SemanticManifest.Dimension dimension = semantic.requireDimension(dimensionId);
            if (!subjectTable.equals(dimension.getTable())) {
                throw exception(FUNNEL_DIMENSION_UNSUPPORTED, dimensionId + " -> " + subjectTable);
            }
            sensitiveDimension |= dimension.isSensitive();
        }
        int limit = dsl.getLimit() == null ? properties.getMaxRows() : dsl.getLimit();
        if (limit < 1 || limit > properties.getMaxRows()) {
            throw exception(DSL_INVALID, "limit 必须在 [1, " + properties.getMaxRows() + "]");
        }
        // T3.5：funnel 在入口独立分流，guardrail 必须显式补上，不能因为「按主体聚合」
        // 就默认放行敏感主体/维度的大结果集（M2 只做标记校验，完整累计检测归 M4）。
        boolean sensitive = sensitiveDimension
                || subject.isExistenceSensitive()
                || subject.getSensitivity() == OntologySnapshot.Sensitivity.PII
                || subject.getSensitivity() == OntologySnapshot.Sensitivity.LABEL;
        if (sensitive && limit > properties.getSensitiveUngroupedLimit()) {
            throw exception(SENSITIVE_BULK_EXPORT_REJECTED,
                    "funnel 敏感主体/维度且 limit=" + limit);
        }
        dsl.setLimit(limit);
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
        validateBooleanMetric(dsl, manifest, mode);
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
        if (mode == QueryDsl.AnalysisMode.LOOKUP_BATCH) {
            guardSensitiveBulkLookup(dsl, ontology);
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

    /**
     * T3.5：lookup_batch 投影敏感属性且请求行数超阈值时按「批量导出形状」拒绝。
     * M2 编译侧只做标记校验，完整累计检测（sensitive_query_event 落库 + 窗口聚合）归 M4。
     * 单实体 lookup 固定 LIMIT 1，天然不构成批量导出，无需检查。
     */
    private void guardSensitiveBulkLookup(QueryDsl dsl, OntologySnapshot ontology) {
        boolean sensitive = false;
        for (String attributeId : dsl.getAttributes() == null ? List.<String>of() : dsl.getAttributes()) {
            OntologySnapshot.Property property = ontology.propertyIndex().get(attributeId);
            if (property != null && (property.isExistenceSensitive()
                    || property.getSensitivity() == OntologySnapshot.Sensitivity.PII
                    || property.getSensitivity() == OntologySnapshot.Sensitivity.LABEL)) {
                sensitive = true;
                break;
            }
        }
        if (!sensitive) {
            return;
        }
        int rows = dsl.getIdentities() == null ? 0 : dsl.getIdentities().size();
        if (rows > properties.getSensitiveUngroupedLimit()) {
            throw exception(SENSITIVE_BULK_EXPORT_REJECTED,
                    "敏感属性批量 lookup " + rows + " 行 > " + properties.getSensitiveUngroupedLimit());
        }
    }

    /**
     * T3.4：布尔判定列校验。Rule 是布尔判定口径的唯一合法载体，运行时只认 published；
     * 表达式三元组（metricId / operator / threshold）必须可解析且引用已发布指标。
     * 用户临时阈值不得即时转 Rule（v1.4 §5.9），DSL 也不允许携带表达式。
     */
    private void validateBooleanMetric(QueryDsl dsl, PublishedManifest manifest,
                                       QueryDsl.AnalysisMode mode) {
        String booleanMetricId = dsl.getBooleanMetricId();
        if (isBlank(booleanMetricId)) {
            return;
        }
        SemanticManifest.Metric booleanMetric = manifest.getSemantic().requireMetric(booleanMetricId);
        if (isBlank(booleanMetric.getRuleId())) {
            throw exception(DSL_INVALID, "booleanMetricId 不是 ruleId 型布尔指标: " + booleanMetricId);
        }
        if (!booleanMetric.getAllowedModes().contains(mode.name())) {
            throw exception(ASSET_MODE_NOT_ALLOWED, booleanMetricId + " / " + mode.name());
        }
        ManifestPayload.Rule rule = manifest.getRules().stream()
                .filter(candidate -> booleanMetric.getRuleId().equals(candidate.getId()))
                .findFirst()
                .orElseThrow(() -> exception(RULE_NOT_FOUND, booleanMetric.getRuleId()));
        if (!"published".equals(rule.getStatus())) {
            throw exception(RULE_NOT_PUBLISHED, rule.getId() + " status=" + rule.getStatus());
        }
        Map<String, Object> expression = rule.getExpression();
        Object rawMetricId = expression == null ? null : expression.get("metricId");
        String operator = expression == null ? null : String.valueOf(expression.get("operator"));
        Object threshold = expression == null ? null : expression.get("threshold");
        // 按类型判断而不是强转：清单规则表达式未被发布校验覆盖，误配不能变成 500
        if (!(rawMetricId instanceof String metricId) || metricId.isBlank()
                || operator == null || !RULE_OPERATORS.contains(operator) || threshold == null) {
            throw exception(RULE_EXPRESSION_INVALID, rule.getId());
        }
        manifest.getSemantic().requireMetric(metricId);
        try {
            new BigDecimal(String.valueOf(threshold));
        } catch (NumberFormatException ex) {
            throw exception(RULE_EXPRESSION_INVALID, "threshold 非数值: " + threshold);
        }
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

    private static boolean samePhysicalMapping(OntologySnapshot.EventDef left,
                                               OntologySnapshot.EventDef right) {
        return Objects.equals(left.getEventTypeProperty(), right.getEventTypeProperty())
                && Objects.equals(left.getTimeProperty(), right.getTimeProperty())
                && Objects.equals(left.getParticipantProperty(), right.getParticipantProperty());
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
        boolean funnel = mode == QueryDsl.AnalysisMode.FUNNEL;
        boolean yoyMom = mode == QueryDsl.AnalysisMode.YOY_MOM;
        if (!funnel && !yoyMom) {
            if (params != null) {
                throw exception(DSL_INVALID, "analysisParams 仅允许 funnel / yoy_mom 模式");
            }
            return;
        }
        // yoy_mom 的 period、funnel 的 steps 都是必填；缺整个对象时先前会一路漏到编译器
        // 触发 NPE（500），这里必须在校验层就按业务错误拒绝。
        if (params == null) {
            throw exception(DSL_INVALID, (funnel ? "funnel" : "yoy_mom") + " 缺少 analysisParams");
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
        // Set.of(...).contains(null) 会抛 NPE；必须先判空再查集合
        if (isBlank(params.getPeriod()) || !YOY_MOM_PERIODS.contains(params.getPeriod())) {
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
            QueryDsl.AnalysisMode subMode = sub.getMode() == null
                    ? QueryDsl.AnalysisMode.QUERY : sub.getMode();
            if (subMode != QueryDsl.AnalysisMode.QUERY) {
                // 子意图 DSL 只有 metric/dimensions/filters/timeExpressionRaw，
                // 表达不了 lookup 的实体与标识，也表达不了 yoy_mom 的周期参数。
                throw exception(DSL_INVALID, "M2 复合意图仅支持 QUERY 子意图: " + sub.getSubIntentId());
            }
        }
    }

    /**
     * 复合意图容器校验（M2/W4/T4.3，无依赖子集）。
     *
     * <p>复合请求本身只是容器：不携带指标、维度、过滤与时间窗，全部下放到子意图。
     * 允许混写会让「这个过滤条件作用于整体还是某个子意图」变成隐含约定，
     * 因此一律拒绝，由调用方把参数挪到具体 subIntent 上。
     */
    public void validateComposite(QueryDsl dsl, PublishedManifest manifest) {
        if (dsl == null) {
            throw invalidParamException("查询 DSL 不能为空");
        }
        if (dsl.getSubIntents() == null || dsl.getSubIntents().isEmpty()) {
            throw exception(DSL_INVALID, "复合意图缺少 subIntents");
        }
        QueryDsl.AnalysisMode mode = dsl.getMode() == null ? QueryDsl.AnalysisMode.QUERY : dsl.getMode();
        if (mode != QueryDsl.AnalysisMode.QUERY) {
            throw exception(DSL_INVALID, "复合意图只支持 QUERY 容器模式: " + mode);
        }
        if (!isBlank(dsl.getMetricId())) {
            throw exception(DSL_INVALID, "复合意图不允许同时指定顶层 metricId");
        }
        if (dsl.getDimensions() != null && !dsl.getDimensions().isEmpty()) {
            throw exception(DSL_INVALID, "复合意图不允许顶层 dimensions，请下放到 subIntents");
        }
        if (dsl.getFilters() != null && !dsl.getFilters().isEmpty()) {
            throw exception(DSL_INVALID, "复合意图不允许顶层 filters，请下放到 subIntents");
        }
        if (dsl.getTimeRange() != null || dsl.getExplicitRange() != null
                || !isBlank(dsl.getTimeExpressionRaw())) {
            throw exception(DSL_INVALID, "复合意图不允许顶层时间窗，请下放到 subIntents");
        }
        if (dsl.getAnalysisParams() != null) {
            throw exception(DSL_INVALID, "复合意图不接受 analysisParams");
        }
        // limit 不是查询语义而是资源上限：容器级 limit 作为每个子意图的默认上限，
        // 不在这里校验就会被子意图的默认值覆盖（等于静默丢弃客户端设置）。
        if (dsl.getLimit() != null
                && (dsl.getLimit() < 1 || dsl.getLimit() > properties.getMaxRows())) {
            throw exception(DSL_INVALID, "复合意图 limit 必须在 [1, " + properties.getMaxRows() + "]");
        }
        if (!isBlank(dsl.getEntityId()) || dsl.getIdentity() != null
                || (dsl.getIdentities() != null && !dsl.getIdentities().isEmpty())
                || (dsl.getAttributes() != null && !dsl.getAttributes().isEmpty())
                || !isBlank(dsl.getIdentityPropertyId()) || !isBlank(dsl.getBooleanMetricId())) {
            throw exception(DSL_INVALID, "复合意图不接受 lookup 字段");
        }
        validateSubIntents(dsl, manifest.getSemantic());
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

}
