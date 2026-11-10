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
import static com.ontos.module.query.enums.ErrorCodeConstants.ATTRIBUTION_DIMENSION_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.ATTRIBUTION_EVENT_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.ATTRIBUTION_MODEL_NOT_IMPLEMENTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.CALIBER_AMBIGUOUS;
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
import static com.ontos.module.query.enums.ErrorCodeConstants.RETENTION_DIMENSION_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.RETENTION_EVENT_NOT_FOUND;
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
    /** retention cohort 周期单位封闭枚举（规格 v1.1 §1.1）：周一 / 每月 1 日起。 */
    private static final Set<String> RETENTION_PERIOD_UNITS = Set.of("WEEK", "MONTH");
    /** attribution 触点回溯窗口封闭枚举（规格 v1.1 §2.1）。 */
    private static final Set<String> ATTRIBUTION_WINDOWS = Set.of("7d", "14d", "30d");
    /** 已写入契约但首发未实现的归因模型（引用即结构化拒绝，作为 approximation_of 的登记位）。 */
    private static final Set<String> ATTRIBUTION_DECLARED_MODELS = Set.of("first_touch", "linear");
    /** 首发约定的渠道维度（规格 §2.1「首发固定支撑 dim.channel」；多域接入时改为清单元数据）。 */
    private static final String CHANNEL_DIMENSION_ID = "dim.channel";
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
        // M6/T1.3：retention 同样是事件锚点（无 metric），时间窗由服务端按 cohort 事件回填
        if (mode == QueryDsl.AnalysisMode.RETENTION) {
            validateRetention(dsl, manifest);
            return;
        }
        rejectLookupOnlyFields(dsl);
        SemanticManifest semantic = manifest.getSemantic();
        SemanticManifest.Metric metric = validateMode(dsl, manifest);
        // M6/T1.4：归因是聚合锚点（metricId 必填），但维度/时间/事件的约束与通用聚合不同
        if (mode == QueryDsl.AnalysisMode.ATTRIBUTION) {
            validateAttribution(dsl, manifest, metric);
            return;
        }

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
        validateCaliberVariant(dsl, semantic, metric);

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
        // RETENTION 与 FUNNEL 同为事件锚点（无 metric），在 validate() 内独立分流校验。
        if (mode == QueryDsl.AnalysisMode.LOOKUP || mode == QueryDsl.AnalysisMode.LOOKUP_BATCH
                || mode == QueryDsl.AnalysisMode.FUNNEL || mode == QueryDsl.AnalysisMode.RETENTION) {
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
        // QUERY / YOY_MOM / ATTRIBUTION 在本类或专用分支编译；LOOKUP / LOOKUP_BATCH / FUNNEL / RETENTION 在入口分流；
        // DISCOVERY 一律拒绝。
        if (mode != QueryDsl.AnalysisMode.QUERY && mode != QueryDsl.AnalysisMode.YOY_MOM
                && mode != QueryDsl.AnalysisMode.ATTRIBUTION) {
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
        // M6/T1.5：顶层 filters = 全局过滤（模式规格 v1.1 §3），校验与维度分组同规，
        // 具体字段在主体解析之后逐个校验（必须映射主体表）。步级过滤不引入。
        if (!isBlank(dsl.getCaliberVariantId())) {
            throw exception(DSL_INVALID, "funnel 不接受 caliberVariantId（口径变体只作用于指标）");
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
        // 全局过滤：字段同样只能映射主体表（时间维度都在订单/事件表，天然被拒，
        // 与规格「时间维度过滤继续拒绝」一致）；值数量按算子校验，不做静默兜底。
        for (QueryDsl.Filter filter : dsl.getFilters() == null ? List.<QueryDsl.Filter>of() : dsl.getFilters()) {
            SemanticManifest.Dimension dimension = semantic.requireDimension(filter.getFieldId());
            if (!subjectTable.equals(dimension.getTable())) {
                throw exception(FUNNEL_DIMENSION_UNSUPPORTED, filter.getFieldId() + " -> " + subjectTable);
            }
            validateFilterValues(filter);
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

    /**
     * retention 校验（M6/T1.3，模式规格 v1.1 §1）。
     *
     * <p>五条硬边界：无 metric 锚点（按事件对口径）；cohort / return 事件各命中唯一定义、
     * 三件物理映射齐备且两者映射一致（否则 SQL 按首事件解读另一事件 = 静默错数）；
     * 维度只允许映射主体表（事件级维度后续支持）；时间窗只接受服务端按 cohort 事件
     * timeProperty 回填的 timeRange（客户端提交由 TimeWindowResolver 前置拒绝，
     * 绝对日期通道 explicitRange 在本层拒绝）。
     */
    private void validateRetention(QueryDsl dsl, PublishedManifest manifest) {
        OntologySnapshot ontology = manifest.getOntology();
        SemanticManifest semantic = manifest.getSemantic();
        if (!isBlank(dsl.getMetricId())) {
            throw exception(DSL_INVALID, "retention 不接受 metricId（按事件主体计数）");
        }
        if (dsl.getExplicitRange() != null) {
            throw exception(DSL_INVALID, "retention 不接受绝对日期（explicitRange）");
        }
        if (dsl.getSubIntents() != null && !dsl.getSubIntents().isEmpty()) {
            throw exception(DSL_INVALID, "retention 不接受 subIntents");
        }
        if (dsl.getFilters() != null && !dsl.getFilters().isEmpty()) {
            // 留存 CTE 没有接 filters 的位置；静默忽略会让「华东留存」返回全域数字。
            throw exception(DSL_INVALID, "retention 暂不支持 filters（未定档，拒绝而非静默忽略）");
        }
        if (!isBlank(dsl.getCaliberVariantId())) {
            throw exception(DSL_INVALID, "retention 不接受 caliberVariantId（口径变体只作用于指标）");
        }
        if (!isBlank(dsl.getEntityId()) || dsl.getIdentity() != null
                || (dsl.getIdentities() != null && !dsl.getIdentities().isEmpty())
                || (dsl.getAttributes() != null && !dsl.getAttributes().isEmpty())
                || !isBlank(dsl.getIdentityPropertyId()) || !isBlank(dsl.getBooleanMetricId())) {
            throw exception(DSL_INVALID, "retention 不接受 lookup 字段");
        }
        QueryDsl.AnalysisParams params = dsl.getAnalysisParams();
        if (params == null) {
            throw exception(DSL_INVALID, "retention 缺少 analysisParams");
        }
        validateAnalysisParams(dsl, QueryDsl.AnalysisMode.RETENTION);

        // 时间窗：只能来自服务端对 timeExpressionRaw 的解析结果（客户端 timeRange 在解析器前置拒绝）
        QueryDsl.TimeRange range = dsl.getTimeRange();
        if (range == null || range.getFrom() == null || range.getTo() == null
                || !QueryDsl.TimeRange.SOURCE_EXPRESSION.equals(range.getSource())
                || isBlank(range.getField()) || isBlank(dsl.getTimePatternId())
                || isBlank(dsl.getTimeParserVersion())) {
            throw exception(DSL_INVALID, "retention 的时间窗必须由服务端解析 timeExpressionRaw 后回填");
        }
        if (range.getFrom().isAfter(range.getTo())) {
            throw exception(DSL_INVALID, "retention 观察窗起点晚于终点");
        }
        OntologySnapshot.Property subject = ontology.propertyIndex().get(params.getSubjectPropertyId());
        if (subject == null || tableOf(subject.getColumn()) == null) {
            throw exception(DSL_INVALID,
                    "retention subjectPropertyId 无法解析物理列: " + params.getSubjectPropertyId());
        }
        OntologySnapshot.EventDef cohortEvent =
                requireRetentionEvent(ontology, params.getCohortEvent(), "cohortEvent");
        OntologySnapshot.EventDef returnEvent =
                requireRetentionEvent(ontology, params.getReturnEvent(), "returnEvent");
        for (OntologySnapshot.EventDef event : List.of(cohortEvent, returnEvent)) {
            if (!Objects.equals(subject.getConceptId(), event.getParticipantClass())) {
                throw exception(RETENTION_EVENT_NOT_FOUND,
                        "participantClass 与主体不一致: " + event.getId());
            }
        }
        if (!samePhysicalMapping(cohortEvent, returnEvent)) {
            throw exception(DSL_INVALID,
                    "cohort / return 事件物理映射不一致（判定列/时间列/主体列必须相同）");
        }
        // field 由服务端按 cohort 事件 timeProperty 回填；被换成别的属性即视为伪造时间字段
        if (!Objects.equals(range.getField(), cohortEvent.getTimeProperty())) {
            throw exception(DSL_INVALID,
                    "retention timeRange.field 必须是 cohort 事件时间属性: " + cohortEvent.getTimeProperty());
        }

        String subjectTable = tableOf(subject.getColumn());
        boolean sensitiveDimension = false;
        for (String dimensionId : dsl.getDimensions() == null ? List.<String>of() : dsl.getDimensions()) {
            SemanticManifest.Dimension dimension = semantic.requireDimension(dimensionId);
            if (!subjectTable.equals(dimension.getTable())) {
                throw exception(RETENTION_DIMENSION_UNSUPPORTED, dimensionId + " -> " + subjectTable);
            }
            sensitiveDimension |= dimension.isSensitive();
        }
        int limit = dsl.getLimit() == null ? properties.getMaxRows() : dsl.getLimit();
        if (limit < 1 || limit > properties.getMaxRows()) {
            throw exception(DSL_INVALID, "limit 必须在 [1, " + properties.getMaxRows() + "]");
        }
        boolean sensitive = sensitiveDimension
                || subject.isExistenceSensitive()
                || subject.getSensitivity() == OntologySnapshot.Sensitivity.PII
                || subject.getSensitivity() == OntologySnapshot.Sensitivity.LABEL;
        if (sensitive && limit > properties.getSensitiveUngroupedLimit()) {
            throw exception(SENSITIVE_BULK_EXPORT_REJECTED, "retention 敏感主体/维度且 limit=" + limit);
        }
        dsl.setLimit(limit);
    }

    /**
     * attribution 校验（M6/T1.4，模式规格 v1.1 §2）。
     *
     * <p>四条硬边界：聚合锚点（metricId 必填且 allowed_modes 含 ATTRIBUTION，由 validateMode 保证）；
     * 转化 / 触点事件各命中唯一已发布定义、三件物理映射齐备且同表，触点事件必须声明
     * channelPropertyId（渠道物理来源，禁止自由文本）；维度只允许渠道维度或指标同表维度
     * （取值时点 = 转化时点）；时间窗只接受服务端按转化事件 timeProperty 回填的 timeRange。
     */
    private void validateAttribution(QueryDsl dsl, PublishedManifest manifest, SemanticManifest.Metric metric) {
        OntologySnapshot ontology = manifest.getOntology();
        SemanticManifest semantic = manifest.getSemantic();
        if (dsl.getExplicitRange() != null) {
            throw exception(DSL_INVALID, "attribution 不接受绝对日期（explicitRange）");
        }
        if (dsl.getSubIntents() != null && !dsl.getSubIntents().isEmpty()) {
            throw exception(DSL_INVALID, "attribution 不接受 subIntents");
        }
        if (dsl.getFilters() != null && !dsl.getFilters().isEmpty()) {
            // 归因 CTE 没有接 filters 的位置；静默忽略会让「某区域归因」返回全域数字
            throw exception(DSL_INVALID, "attribution 暂不支持 filters（未定档，拒绝而非静默忽略）");
        }
        if (!isBlank(dsl.getCaliberVariantId())) {
            // 首发归因指标（gmv / order_count）无口径变体；带变体的指标接入需先定档条件下推位置
            throw exception(DSL_INVALID, "attribution 暂不支持 caliberVariantId（首发未定档）");
        }
        QueryDsl.AnalysisParams params = dsl.getAnalysisParams();
        if (params == null) {
            throw exception(DSL_INVALID, "attribution 缺少 analysisParams");
        }
        validateAnalysisParams(dsl, QueryDsl.AnalysisMode.ATTRIBUTION);

        QueryDsl.TimeRange range = dsl.getTimeRange();
        if (range == null || range.getFrom() == null || range.getTo() == null
                || !QueryDsl.TimeRange.SOURCE_EXPRESSION.equals(range.getSource())
                || isBlank(range.getField()) || isBlank(dsl.getTimePatternId())
                || isBlank(dsl.getTimeParserVersion())) {
            throw exception(DSL_INVALID, "attribution 的时间窗必须由服务端解析 timeExpressionRaw 后回填");
        }
        if (range.getFrom().isAfter(range.getTo())) {
            throw exception(DSL_INVALID, "attribution 转化窗起点晚于终点");
        }
        OntologySnapshot.Property subject = ontology.propertyIndex().get(params.getSubjectPropertyId());
        if (subject == null || tableOf(subject.getColumn()) == null) {
            throw exception(DSL_INVALID,
                    "attribution subjectPropertyId 无法解析物理列: " + params.getSubjectPropertyId());
        }
        OntologySnapshot.EventDef conversionEvent = requireAttributionEvent(
                ontology, params.getConversionEvent(), "conversionEvent", subject, false);
        OntologySnapshot.EventDef touchEvent = requireAttributionEvent(
                ontology, params.getTouchEvent(), "touchEvent", subject, true);
        String eventTable = tableOf(requireEventMappingProperty(
                ontology, conversionEvent, conversionEvent.getTimeProperty()).getColumn());
        if (eventTable == null || !sameTable(ontology, conversionEvent, eventTable)
                || !sameTable(ontology, touchEvent, eventTable)) {
            throw exception(ATTRIBUTION_EVENT_NOT_FOUND,
                    "转化 / 触点事件的判定列、时间列、主体列必须同表: " + eventTable);
        }
        if (!Objects.equals(range.getField(), conversionEvent.getTimeProperty())) {
            throw exception(DSL_INVALID,
                    "attribution timeRange.field 必须是转化事件时间属性: " + conversionEvent.getTimeProperty());
        }

        boolean sensitiveDimension = false;
        for (String dimensionId : dsl.getDimensions() == null ? List.<String>of() : dsl.getDimensions()) {
            SemanticManifest.Dimension dimension = semantic.requireDimension(dimensionId);
            sensitiveDimension |= dimension.isSensitive();
            if (CHANNEL_DIMENSION_ID.equals(dimensionId)) {
                continue; // 渠道维度值来自触点渠道映射（channel.name），不是订单冗余列
            }
            if (!Objects.equals(dimension.getTable(), metric.getTable())) {
                throw exception(ATTRIBUTION_DIMENSION_UNSUPPORTED,
                        dimensionId + " -> " + metric.getTable());
            }
        }
        int limit = dsl.getLimit() == null ? properties.getMaxRows() : dsl.getLimit();
        if (limit < 1 || limit > properties.getMaxRows()) {
            throw exception(DSL_INVALID, "limit 必须在 [1, " + properties.getMaxRows() + "]");
        }
        boolean sensitive = sensitiveDimension || metric.isSensitive();
        if (sensitive && (dsl.getDimensions() == null || dsl.getDimensions().isEmpty())
                && limit > properties.getSensitiveUngroupedLimit()) {
            throw exception(SENSITIVE_BULK_EXPORT_REJECTED,
                    metric.getId() + " 归因无维度分组且 limit=" + limit);
        }
        dsl.setLimit(limit);
    }

    /**
     * 归因事件：唯一已发布定义 + 三件物理映射齐备 + participantClass 与主体一致；
     * 触点事件额外要求 channelPropertyId（渠道物理来源）已发布、可过滤且与事件映射同表。
     */
    private static OntologySnapshot.EventDef requireAttributionEvent(OntologySnapshot ontology, String eventType,
                                                                     String field,
                                                                     OntologySnapshot.Property subject,
                                                                     boolean requireChannel) {
        if (isBlank(eventType)) {
            throw exception(DSL_INVALID, "attribution 缺少 " + field);
        }
        List<OntologySnapshot.EventDef> matched = ontology.getEvents().stream()
                .filter(event -> eventType.equals(event.getEventType()))
                .toList();
        if (matched.size() != 1) {
            throw exception(ATTRIBUTION_EVENT_NOT_FOUND,
                    eventType + " 命中 " + matched.size() + " 个事件定义");
        }
        OntologySnapshot.EventDef event = matched.get(0);
        if (isBlank(event.getEventTypeProperty()) || isBlank(event.getParticipantProperty())
                || isBlank(event.getTimeProperty())) {
            throw exception(ATTRIBUTION_EVENT_NOT_FOUND, event.getId() + " 缺少物理映射");
        }
        if (!Objects.equals(subject.getConceptId(), event.getParticipantClass())) {
            throw exception(ATTRIBUTION_EVENT_NOT_FOUND,
                    "participantClass 与主体不一致: " + event.getId());
        }
        if (!requireChannel) {
            return event;
        }
        OntologySnapshot.Property channel = isBlank(event.getChannelPropertyId())
                ? null : ontology.propertyIndex().get(event.getChannelPropertyId());
        if (channel == null) {
            throw exception(ATTRIBUTION_EVENT_NOT_FOUND,
                    event.getId() + " 未声明 channelPropertyId（触点渠道物理来源）");
        }
        if (!channel.isFilterable()) {
            throw exception(ATTRIBUTION_EVENT_NOT_FOUND, "渠道属性不可过滤: " + channel.getId());
        }
        String eventTable = tableOf(requireEventMappingProperty(ontology, event, event.getTimeProperty()).getColumn());
        if (!Objects.equals(tableOf(channel.getColumn()), eventTable)) {
            throw exception(ATTRIBUTION_EVENT_NOT_FOUND,
                    "渠道属性与事件映射不同表: " + channel.getId());
        }
        return event;
    }

    private static OntologySnapshot.Property requireEventMappingProperty(OntologySnapshot ontology,
                                                                        OntologySnapshot.EventDef event,
                                                                        String propertyId) {
        OntologySnapshot.Property property = isBlank(propertyId) ? null : ontology.propertyIndex().get(propertyId);
        if (property == null) {
            throw exception(ATTRIBUTION_EVENT_NOT_FOUND, event.getId() + " 映射属性未发布: " + propertyId);
        }
        return property;
    }

    private static boolean sameTable(OntologySnapshot ontology, OntologySnapshot.EventDef event, String table) {
        for (String propertyId : List.of(event.getEventTypeProperty(), event.getTimeProperty(),
                event.getParticipantProperty())) {
            OntologySnapshot.Property property = requireEventMappingProperty(ontology, event, propertyId);
            if (!Objects.equals(table, tableOf(property.getColumn()))) {
                return false;
            }
        }
        return true;
    }

    /** cohort / return 事件：必须命中唯一已发布定义且三件物理映射齐备（不得从命名习惯推断）。 */
    private static OntologySnapshot.EventDef requireRetentionEvent(OntologySnapshot ontology, String eventType,
                                                                  String field) {
        if (isBlank(eventType)) {
            throw exception(DSL_INVALID, "retention 缺少 " + field);
        }
        List<OntologySnapshot.EventDef> matched = ontology.getEvents().stream()
                .filter(event -> eventType.equals(event.getEventType()))
                .toList();
        if (matched.size() != 1) {
            throw exception(RETENTION_EVENT_NOT_FOUND, eventType + " 命中 " + matched.size() + " 个事件定义");
        }
        OntologySnapshot.EventDef event = matched.get(0);
        if (isBlank(event.getEventTypeProperty()) || isBlank(event.getParticipantProperty())
                || isBlank(event.getTimeProperty())) {
            throw exception(RETENTION_EVENT_NOT_FOUND, event.getId() + " 缺少物理映射");
        }
        return event;
    }

    /**
     * 口径变体闸门（M3/W2，v1.4 §5.4）：带变体的指标必须显式选择，缺失转澄清；
     * 变体必须属于该指标。禁止 LLM 在候选里二选一——歧义必须回到人。
     */
    private void validateCaliberVariant(QueryDsl dsl, SemanticManifest semantic,
                                       SemanticManifest.Metric metric) {
        List<SemanticManifest.CaliberVariant> variants = metric.getCaliberVariants() == null
                ? List.of() : metric.getCaliberVariants();
        if (variants.isEmpty()) {
            if (!isBlank(dsl.getCaliberVariantId())) {
                throw exception(DSL_INVALID,
                        "指标没有口径变体，不接受 caliberVariantId: " + dsl.getCaliberVariantId());
            }
            return;
        }
        if (isBlank(dsl.getCaliberVariantId())) {
            List<String> candidates = new ArrayList<>();
            for (SemanticManifest.CaliberVariant variant : variants) {
                String alias = variant.getAlias() == null || variant.getAlias().isEmpty()
                        ? "" : "（" + variant.getAlias().get(0) + "）";
                candidates.add(variant.getVariantId() + alias);
            }
            throw exception(CALIBER_AMBIGUOUS, metric.getId() + " 候选: " + String.join(" / ", candidates));
        }
        SemanticManifest.CaliberVariant matched = variants.stream()
                .filter(candidate -> dsl.getCaliberVariantId().equals(candidate.getVariantId()))
                .findFirst()
                .orElseThrow(() -> exception(DSL_INVALID,
                        "caliberVariantId 不属于该指标: " + dsl.getCaliberVariantId()));
        SemanticManifest.VariantCondition condition = matched.getCondition();
        if (condition == null || isBlank(condition.getFieldId())) {
            throw exception(DSL_INVALID, "变体缺少 condition.fieldId: " + matched.getVariantId());
        }
        // 与分组/过滤/时间同一条授权口径：变体条件字段也必须在指标允许维度内
        requireAllowedDimension(metric, condition.getFieldId());
        semantic.requireDimension(condition.getFieldId());
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
        if (!isBlank(dsl.getCaliberVariantId())) {
            throw exception(DSL_INVALID, "lookup 不接受 caliberVariantId（口径变体只作用于指标）");
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

    /** funnel / yoy_mom / retention / attribution 的 analysis_params 契约（analysis-mode 规格 v1 / v1.1）。 */
    private void validateAnalysisParams(QueryDsl dsl, QueryDsl.AnalysisMode mode) {
        QueryDsl.AnalysisParams params = dsl.getAnalysisParams();
        boolean funnel = mode == QueryDsl.AnalysisMode.FUNNEL;
        boolean yoyMom = mode == QueryDsl.AnalysisMode.YOY_MOM;
        boolean retention = mode == QueryDsl.AnalysisMode.RETENTION;
        boolean attribution = mode == QueryDsl.AnalysisMode.ATTRIBUTION;
        if (!funnel && !yoyMom && !retention && !attribution) {
            if (params != null) {
                throw exception(DSL_INVALID,
                        "analysisParams 仅允许 funnel / yoy_mom / retention / attribution 模式");
            }
            return;
        }
        // yoy_mom 的 period、funnel 的 steps、retention 的事件对、attribution 的事件对都是必填；
        // 缺整个对象时会一路漏到编译器触发 NPE（500），必须在校验层按业务错误拒绝。
        if (params == null) {
            String label = funnel ? "funnel"
                    : (yoyMom ? "yoy_mom" : (retention ? "retention" : "attribution"));
            throw exception(DSL_INVALID, label + " 缺少 analysisParams");
        }
        if (attribution) {
            if (isBlank(params.getConversionEvent()) || isBlank(params.getTouchEvent())) {
                throw exception(DSL_INVALID,
                        "attribution 缺少 conversionEvent / touchEvent（event_type 值）");
            }
            if (isBlank(params.getAttributionWindow())
                    || !ATTRIBUTION_WINDOWS.contains(params.getAttributionWindow())) {
                throw exception(DSL_INVALID, "attribution attributionWindow 仅允许 7d / 14d / 30d");
            }
            if (isBlank(params.getModel())) {
                throw exception(DSL_INVALID, "attribution 缺少 model（首发仅 last_touch）");
            }
            if (!"last_touch".equals(params.getModel())) {
                // 契约已声明未实现的模型：给出结构化「未实现」而不是笼统 DSL 错误
                if (ATTRIBUTION_DECLARED_MODELS.contains(params.getModel())) {
                    throw exception(ATTRIBUTION_MODEL_NOT_IMPLEMENTED, params.getModel());
                }
                throw exception(DSL_INVALID, "attribution model 仅允许 last_touch: " + params.getModel());
            }
            if (isBlank(params.getSubjectPropertyId())) {
                throw exception(DSL_INVALID, "attribution 缺少 subjectPropertyId（本体属性 ID）");
            }
            if ((params.getSteps() != null && !params.getSteps().isEmpty())
                    || params.getCohortWindow() != null || params.getStepWindow() != null
                    || params.getDeduplication() != null || params.getConversionDenominator() != null
                    || params.getPeriod() != null
                    || params.getCohortEvent() != null || params.getReturnEvent() != null
                    || params.getPeriodUnit() != null || params.getPeriods() != null) {
                throw exception(DSL_INVALID, "attribution 不接受其他分析模式的专属参数");
            }
            return;
        }
        if (retention) {
            if (isBlank(params.getCohortEvent()) || isBlank(params.getReturnEvent())) {
                throw exception(DSL_INVALID, "retention 缺少 cohortEvent / returnEvent（event_type 值）");
            }
            if (isBlank(params.getPeriodUnit()) || !RETENTION_PERIOD_UNITS.contains(params.getPeriodUnit())) {
                throw exception(DSL_INVALID, "retention periodUnit 仅允许 WEEK / MONTH");
            }
            if (params.getPeriods() == null || params.getPeriods() < 2 || params.getPeriods() > 12) {
                throw exception(DSL_INVALID, "retention periods 必须在 [2, 12]");
            }
            if (isBlank(params.getSubjectPropertyId())) {
                throw exception(DSL_INVALID, "retention 缺少 subjectPropertyId（本体属性 ID）");
            }
            // 跨模式参数混用会让编译器按错误模式取字段（静默错数），一律拒绝而非忽略
            if ((params.getSteps() != null && !params.getSteps().isEmpty())
                    || params.getCohortWindow() != null || params.getStepWindow() != null
                    || params.getDeduplication() != null || params.getConversionDenominator() != null
                    || params.getPeriod() != null
                    || params.getConversionEvent() != null || params.getTouchEvent() != null
                    || params.getAttributionWindow() != null || params.getModel() != null) {
                throw exception(DSL_INVALID, "retention 不接受其他分析模式的专属参数");
            }
            return;
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
            if (params.getCohortEvent() != null || params.getReturnEvent() != null
                    || params.getPeriodUnit() != null || params.getPeriods() != null
                    || params.getConversionEvent() != null || params.getTouchEvent() != null
                    || params.getAttributionWindow() != null || params.getModel() != null) {
                throw exception(DSL_INVALID, "funnel 不接受其他分析模式的专属参数");
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
        if (params.getCohortEvent() != null || params.getReturnEvent() != null
                || params.getPeriodUnit() != null || params.getPeriods() != null
                || params.getConversionEvent() != null || params.getTouchEvent() != null
                || params.getAttributionWindow() != null || params.getModel() != null) {
            throw exception(DSL_INVALID, "yoy_mom 不接受其他分析模式的专属参数");
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
                || !isBlank(dsl.getIdentityPropertyId()) || !isBlank(dsl.getBooleanMetricId())
                || !isBlank(dsl.getCaliberVariantId())) {
            throw exception(DSL_INVALID, "复合意图不接受 lookup 字段或口径变体（请下放到子意图）");
        }
        validateSubIntents(dsl, manifest.getSemantic());
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

}
