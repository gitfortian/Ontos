package com.ontos.module.query.core.compiler;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.query.core.time.TimeExpressionParser;
import org.jooq.CommonTableExpression;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.conf.ParamType;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.SelectQuery;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.query.enums.ErrorCodeConstants.ANALYSIS_MODE_NOT_IMPLEMENTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.ATTRIBUTION_EVENT_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_EVENT_MAPPING_MISSING;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_EVENT_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_TIME_WINDOW_MISSING;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_ATTRIBUTE_NOT_PROJECTABLE;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_CROSS_TABLE_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_IDENTITY_REQUIRED;
import static com.ontos.module.query.enums.ErrorCodeConstants.RETENTION_EVENT_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.RETENTION_PERIOD_INCOMPLETE;
import static com.ontos.module.query.enums.ErrorCodeConstants.RULE_EXPRESSION_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.RULE_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.RULE_NOT_PUBLISHED;
import static com.ontos.module.query.enums.ErrorCodeConstants.RULE_SUBJECT_PATH_NOT_UNIQUE;

/**
 * 确定性 SQL 编译器：只依据已发布的语义映射与已校验 DSL 生成 SQL。
 *
 * <p>W3/T3.1：单表聚合之外，支持服务端 UNIQUE 路径展开的 Join。
 * Join 的表名与 ON 列全部来自已发布清单（Relation.fromColumn / toColumn），
 * 用 jOOQ 结构化构造，禁止字符串拼接（ADR-0007）；AMBIGUOUS/NONE 在 JoinPathResolver 已被拒绝。
 */
@Component
public class SqlCompiler {

    private static final String VALUE_ALIAS = "value";
    private static final String IDENTITY_ALIAS = "__identity";
    /** 首发约定的渠道维度与渠道实体属性（模式规格 §2.1「首发固定支撑 dim.channel」）。 */
    private static final String CHANNEL_DIMENSION_ID = "dim.channel";
    private static final String CHANNEL_ID_PROPERTY_ID = "property.channel.channel_id";
    private static final String CHANNEL_NAME_PROPERTY_ID = "property.channel.name";
    /** 事件标识与去重键属性：转化去重计数与 last_touch 稳定排序的物理来源（清单 v12 发布）。 */
    private static final String EVENT_ID_PROPERTY_ID = "property.event.event_id";
    private static final String EVENT_DEDUP_KEY_PROPERTY_ID = "property.event.dedup_key";

    private final DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
    /** 留存观察锚点时钟（Asia/Shanghai）；单测注入固定时钟保证「完整周期」判定可复现。 */
    private Clock clock = Clock.system(ZoneId.of(TimeExpressionParser.ZONE));

    public SqlCompiler() {
    }

    /** 包级可见：仅测试注入固定时钟，Spring 走无参构造。 */
    SqlCompiler(Clock clock) {
        this.clock = clock;
    }

    public CompiledQuery compile(QueryDsl queryDsl, PublishedManifest manifest) {
        QueryDsl.AnalysisMode mode = queryDsl.getMode() == null ? QueryDsl.AnalysisMode.QUERY : queryDsl.getMode();
        // W3/T3.2：lookup 系列以实体为入口，走独立投影构造器
        if (mode == QueryDsl.AnalysisMode.LOOKUP || mode == QueryDsl.AnalysisMode.LOOKUP_BATCH) {
            return compileLookup(queryDsl, manifest, mode);
        }
        // W3/T3.3：同环比走周期骨架 + 窗口函数构造器；funnel 走步进 CTE
        if (mode == QueryDsl.AnalysisMode.YOY_MOM) {
            return compileYoyMom(queryDsl, manifest);
        }
        if (mode == QueryDsl.AnalysisMode.FUNNEL) {
            return compileFunnel(queryDsl, manifest);
        }
        // M6/T1.3：留存走事件锚点 + cohort 周期骨架构造器
        if (mode == QueryDsl.AnalysisMode.RETENTION) {
            return compileRetention(queryDsl, manifest);
        }
        // M6/T1.4：归因走聚合锚点 + 末次触点构造器
        if (mode == QueryDsl.AnalysisMode.ATTRIBUTION) {
            return compileAttribution(queryDsl, manifest);
        }
        // 纵深防御（PoC-04 缺陷修复）：即使调用方绕过校验器，编译器也绝不把
        // 未实现的模式静默编译成聚合 SQL——只拒绝，不实现（funnel / yoy_mom / retention / attribution 均已在上面分流；
        // DISCOVERY 不进 DSL，见 docs/04-specs/analysis-mode-discovery-边界声明-v1.md）。
        if (mode != QueryDsl.AnalysisMode.QUERY) {
            throw exception(ANALYSIS_MODE_NOT_IMPLEMENTED, mode.name());
        }
        SemanticManifest semantic = manifest.getSemantic();
        SemanticManifest.Metric metric = semantic.requireMetric(queryDsl.getMetricId());
        List<String> dimensionIds = queryDsl.getDimensions() == null ? List.of() : queryDsl.getDimensions();
        List<QueryDsl.ResolvedJoin> joins = queryDsl.getResolvedJoins() == null
                ? List.of() : queryDsl.getResolvedJoins();

        SelectQuery<Record> select = dsl.selectQuery();
        select.addSelect(aggregate(metric).as(VALUE_ALIAS));

        List<Field<?>> groupFields = new ArrayList<>();
        for (String dimensionId : dimensionIds) {
            SemanticManifest.Dimension dimension = semantic.requireDimension(dimensionId);
            Field<Object> field = DSL.field(assetColumn(dimension.getTable(), dimension.getColumn()));
            select.addSelect(field.as(DSL.name(dimensionId)));
            groupFields.add(field);
        }
        select.addGroupBy(groupFields);

        select.addFrom(DSL.table(tableName(metric.getTable())));

        List<Condition> conditions = new ArrayList<>();
        addJoins(select, joins);
        if (queryDsl.getFilters() != null) {
            for (QueryDsl.Filter filter : queryDsl.getFilters()) {
                SemanticManifest.Dimension dimension = semantic.requireDimension(filter.getFieldId());
                conditions.add(condition(filter, dimension));
            }
        }
        Condition caliber = caliberCondition(semantic, metric, queryDsl);
        if (caliber != null) {
            conditions.add(caliber);
        }
        if (queryDsl.getTimeRange() != null) {
            conditions.add(timeCondition(queryDsl.getTimeRange(), semantic));
        }
        select.addConditions(conditions);

        int limit = queryDsl.getLimit() == null ? 100 : queryDsl.getLimit();
        select.addLimit(limit);

        Query query = select;
        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId(manifest.getManifestId());
        compiled.setContentHash(manifest.getContentHash());
        compiled.setMode(mode.name());
        compiled.setMetricId(metric.getId());
        compiled.setDimensions(dimensionIds);
        compiled.setJoins(joins);
        compiled.setDialect("postgresql");
        compiled.setSql(query.getSQL(ParamType.INDEXED));
        compiled.setBindValues(query.getBindValues());
        compiled.setAppliedLimit(limit);
        compiled.setCompiledAt(Instant.now());
        return compiled;
    }

    private static void addJoins(SelectQuery<Record> select, List<QueryDsl.ResolvedJoin> joins) {
        for (QueryDsl.ResolvedJoin join : joins == null ? List.<QueryDsl.ResolvedJoin>of() : joins) {
            // 纵深防御：JoinPathResolver 保证每条边都有待引入的表；
            // 缺失时整单拒绝，绝不把未展开的关系退化成 WHERE 等值条件（那会静默改变结果）。
            if (join.getTable() == null) {
                throw exception(DSL_INVALID, "解析后的 Join 缺少待引入表: " + join.getRelationId());
            }
            Condition on = DSL.field(columnName(join.getLeftColumn()))
                    .eq(DSL.field(columnName(join.getRightColumn())));
            select.addJoin(DSL.table(tableName(join.getTable())), on);
        }
    }

    /**
     * 口径变体 → 追加的 WHERE 条件（M3/W2，v1.4 §5.4）。
     *
     * <p>发布期已保证「同表 + 指标授权字段 + 封闭算子」，这里仍按纵深防御重新解析：
     * 编译器不依赖调用顺序，清单被绕过校验装配时宁可拒绝也不产出错误 SQL。
     */
    private Condition caliberCondition(SemanticManifest semantic, SemanticManifest.Metric metric,
                                       QueryDsl queryDsl) {
        String variantId = queryDsl.getCaliberVariantId();
        if (variantId == null || variantId.isBlank()) {
            return null;
        }
        SemanticManifest.CaliberVariant variant = metric.getCaliberVariants() == null ? null
                : metric.getCaliberVariants().stream()
                        .filter(candidate -> variantId.equals(candidate.getVariantId()))
                        .findFirst()
                        .orElse(null);
        if (variant == null || variant.getCondition() == null) {
            throw exception(DSL_INVALID, "caliberVariantId 不属于该指标: " + variantId);
        }
        SemanticManifest.VariantCondition vc = variant.getCondition();
        SemanticManifest.Dimension dimension = semantic.requireDimension(vc.getFieldId());
        if (!java.util.Objects.equals(dimension.getTable(), metric.getTable())) {
            throw exception(DSL_INVALID, "口径变体条件字段与指标不同表: " + vc.getFieldId());
        }
        QueryDsl.Filter filter = new QueryDsl.Filter();
        filter.setFieldId(vc.getFieldId());
        try {
            filter.setOperator(QueryDsl.Operator.valueOf(vc.getOperator()));
        } catch (IllegalArgumentException ex) {
            throw exception(DSL_INVALID, "口径变体算子非法: " + vc.getOperator());
        }
        filter.setValues(new ArrayList<>(vc.getValues() == null ? List.of() : vc.getValues()));
        return condition(filter, dimension);
    }

    /**
     * lookup / lookup_batch 投影编译（v1.4 §6.3.1、M2/T3.2）。
     *
     * <p>单表、按标识属性定位：单实体用 {@code = ?} 且 {@code LIMIT 2} 探测「标识不唯一」
     * （命中 2 行由 service 层拒绝，不静默截断）；批量用参数化 {@code IN}，
     * 逐行可用性由 service 按请求顺序对齐，不因单行缺失整体失败。
     * 物理列仍然只来自已发布本体属性，DSL 不携带物理名。
     */
    private CompiledQuery compileLookup(QueryDsl queryDsl, PublishedManifest manifest,
                                        QueryDsl.AnalysisMode mode) {
        OntologySnapshot ontology = manifest.getOntology();
        String entityId = queryDsl.getEntityId();
        List<String> attributeIds = queryDsl.getAttributes() == null
                ? List.of() : queryDsl.getAttributes();
        List<OntologySnapshot.Property> attributes = new ArrayList<>();
        String table = null;
        for (String attributeId : attributeIds) {
            OntologySnapshot.Property property = ontology.propertyIndex().get(attributeId);
            if (property == null || !entityId.equals(property.getConceptId())
                    || !property.isLookupProjectable()) {
                throw exception(LOOKUP_ATTRIBUTE_NOT_PROJECTABLE, attributeId);
            }
            String current = tableOf(property.getColumn());
            if (current == null || (table != null && !table.equals(current))) {
                throw exception(LOOKUP_CROSS_TABLE_UNSUPPORTED, attributeId);
            }
            table = current;
            attributes.add(property);
        }
        if (attributes.isEmpty() || table == null) {
            throw exception(LOOKUP_ATTRIBUTE_NOT_PROJECTABLE, "attributes 为空");
        }

        String identityPropertyId = mode == QueryDsl.AnalysisMode.LOOKUP
                ? queryDsl.getIdentity() == null ? null : queryDsl.getIdentity().getPropertyId()
                : queryDsl.getIdentityPropertyId();
        OntologySnapshot.Property identity = ontology.propertyIndex().get(identityPropertyId);
        if (identity == null || !entityId.equals(identity.getConceptId())
                || !table.equals(tableOf(identity.getColumn()))) {
            throw exception(LOOKUP_IDENTITY_REQUIRED, "identityPropertyId=" + identityPropertyId);
        }

        SelectQuery<Record> select = dsl.selectQuery();
        select.addSelect(DSL.field(columnName(identity.getColumn())).as(IDENTITY_ALIAS));
        for (OntologySnapshot.Property property : attributes) {
            select.addSelect(DSL.field(assetColumn(table, property.getColumn()))
                    .as(DSL.name(property.getId())));
        }
        if (queryDsl.getBooleanMetricId() != null && !queryDsl.getBooleanMetricId().isBlank()) {
            // T3.4：布尔判定列在编译期展开为 Rule 子查询（M2 规格 §3.1 决策 2）
            select.addSelect(ruleColumn(queryDsl, manifest));
        }
        select.addFrom(DSL.table(tableName(table)));
        int appliedLimit;
        if (mode == QueryDsl.AnalysisMode.LOOKUP) {
            select.addConditions(DSL.field(columnName(identity.getColumn()))
                    .eq(queryDsl.getIdentity().getValue()));
            appliedLimit = 2;
        } else {
            List<String> identities = queryDsl.getIdentities() == null ? List.of() : queryDsl.getIdentities();
            select.addConditions(DSL.field(columnName(identity.getColumn())).in(identities));
            appliedLimit = queryDsl.getLimit() == null ? identities.size() : queryDsl.getLimit();
        }
        select.addLimit(appliedLimit);

        Query query = select;
        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId(manifest.getManifestId());
        compiled.setContentHash(manifest.getContentHash());
        compiled.setMode(mode.name());
        compiled.setEntityId(entityId);
        compiled.setAttributes(attributeIds);
        compiled.setBooleanMetricId(queryDsl.getBooleanMetricId());
        compiled.setIdentities(mode == QueryDsl.AnalysisMode.LOOKUP_BATCH
                ? queryDsl.getIdentities() : null);
        compiled.setDimensions(List.of());
        compiled.setJoins(List.of());
        compiled.setDialect("postgresql");
        compiled.setSql(query.getSQL(ParamType.INDEXED));
        compiled.setBindValues(query.getBindValues());
        compiled.setAppliedLimit(appliedLimit);
        compiled.setCompiledAt(Instant.now());
        return compiled;
    }

    /**
     * T3.4 Rule 求值：布尔指标编译期展开为
     * {@code 实体 IN (SELECT 主体列 FROM 指标表 GROUP BY 主体列 HAVING 聚合 OP 阈值)}
     * （M2 规格 §3.1 决策 2）。表达式三元组全部结构化构造：算子是封闭枚举的 switch，
     * 阈值走绑定值，物理名只来自已发布 Relation / Metric / Property，禁止表达式字符串。
     */
    private Field<Boolean> ruleColumn(QueryDsl queryDsl, PublishedManifest manifest) {
        String booleanMetricId = queryDsl.getBooleanMetricId();
        SemanticManifest.Metric booleanMetric = manifest.getSemantic().requireMetric(booleanMetricId);
        if (booleanMetric.getRuleId() == null) {
            throw exception(RULE_EXPRESSION_INVALID, "不是 ruleId 型布尔指标: " + booleanMetricId);
        }
        ManifestPayload.Rule rule = manifest.getRules().stream()
                .filter(candidate -> booleanMetric.getRuleId().equals(candidate.getId()))
                .findFirst()
                .orElseThrow(() -> exception(RULE_NOT_FOUND, booleanMetric.getRuleId()));
        if (!"published".equals(rule.getStatus())) {
            throw exception(RULE_NOT_PUBLISHED, rule.getId());
        }
        Map<String, Object> expression = rule.getExpression();
        Object rawMetricId = expression == null ? null : expression.get("metricId");
        String operator = expression == null ? null : String.valueOf(expression.get("operator"));
        // 清单由服务端发布，但规则表达式结构未被发布校验覆盖——这里按类型判断，
        // 避免 Admin 误配（metricId 写成数字）在查询期变成 ClassCastException（500）。
        if (!(rawMetricId instanceof String metricId) || metricId.isBlank()
                || operator == null || expression.get("threshold") == null) {
            throw exception(RULE_EXPRESSION_INVALID, rule.getId());
        }
        SemanticManifest.Metric metric = manifest.getSemantic().requireMetric(metricId);
        BigDecimal threshold;
        try {
            threshold = new BigDecimal(String.valueOf(expression.get("threshold")));
        } catch (NumberFormatException ex) {
            throw exception(RULE_EXPRESSION_INVALID, "threshold 非数值: " + expression.get("threshold"));
        }

        OntologySnapshot.Concept concept = manifest.getOntology().conceptIndex().get(queryDsl.getEntityId());
        OntologySnapshot.Property subjectProperty = concept == null ? null
                : manifest.getOntology().propertyIndex().get(concept.getIdentityProperty());
        if (subjectProperty == null || tableOf(subjectProperty.getColumn()) == null) {
            throw exception(RULE_SUBJECT_PATH_NOT_UNIQUE, "主体标识属性缺失");
        }
        String subjectColumn = subjectProperty.getColumn();
        String subjectTable = tableOf(subjectColumn);
        String metricTable = metric.getTable();

        // 连接「主体表 ↔ 规则指标表」的关系必须唯一，禁止猜测关联列
        List<OntologySnapshot.Relation> matched = new ArrayList<>();
        List<OntologySnapshot.Relation> relations = manifest.getOntology().getRelations() == null
                ? List.of() : manifest.getOntology().getRelations();
        for (OntologySnapshot.Relation relation : relations) {
            String fromTable = tableOf(relation.getFromColumn());
            String toTable = tableOf(relation.getToColumn());
            if ((subjectTable.equals(fromTable) && metricTable.equals(toTable))
                    || (subjectTable.equals(toTable) && metricTable.equals(fromTable))) {
                matched.add(relation);
            }
        }
        if (matched.size() != 1) {
            throw exception(RULE_SUBJECT_PATH_NOT_UNIQUE,
                    subjectTable + " -> " + metricTable + " 命中 " + matched.size() + " 条关系");
        }
        OntologySnapshot.Relation relation = matched.get(0);
        String groupColumn = metricTable.equals(tableOf(relation.getFromColumn()))
                ? relation.getFromColumn() : relation.getToColumn();

        Field<BigDecimal> aggregated = DSL.field("{0}", BigDecimal.class, aggregate(metric));
        Field<BigDecimal> bound = DSL.val(threshold, BigDecimal.class);
        Condition having = switch (operator) {
            case "GTE" -> aggregated.ge(bound);
            case "GT" -> aggregated.gt(bound);
            case "LTE" -> aggregated.le(bound);
            case "LT" -> aggregated.lt(bound);
            case "EQ" -> aggregated.eq(bound);
            case "NEQ" -> aggregated.ne(bound);
            default -> throw exception(RULE_EXPRESSION_INVALID, "不支持的算子: " + operator);
        };

        Field<Object> groupField = DSL.field(columnName(groupColumn));
        SelectQuery<Record> subquery = dsl.selectQuery();
        subquery.addSelect(groupField);
        subquery.addFrom(DSL.table(tableName(metricTable)));
        subquery.addGroupBy(groupField);
        subquery.addHaving(having);
        return DSL.field("{0} in ({1})", Boolean.class,
                DSL.field(columnName(subjectColumn)), subquery).as(DSL.name(booleanMetricId));
    }

    /**
     * funnel 步进 CTE 编译（analysis-mode 规格 v1 §1.3、ADR-0012）。
     *
     * <p>cohort = 首步事件在窗口内的主体 + 首次发生时间（维度取首次触点时的主体属性值）；
     * step_k = 在前一步 step_time 之后、step_window 之内发生第 k 步的主体 + 首次时间；
     * 最终按维度分组产出 count(distinct subject) per step，转化率分母固定为上一步。
     * 事件类型过滤值只来自已发布 EventDef 的 `eventTypeValue`，不接受 DSL 里的物理条件。
     */
    private CompiledQuery compileFunnel(QueryDsl queryDsl, PublishedManifest manifest) {
        OntologySnapshot ontology = manifest.getOntology();
        SemanticManifest semantic = manifest.getSemantic();
        QueryDsl.AnalysisParams params = queryDsl.getAnalysisParams();
        // 纵深防御：校验器已保证 steps/cohortWindow 完整，编译器不依赖调用顺序
        if (params == null || params.getSteps() == null || params.getSteps().size() < 2) {
            throw exception(DSL_INVALID, "funnel 缺少合法 steps");
        }
        if (params.getCohortWindow() == null || params.getCohortWindow().getFrom() == null
                || params.getCohortWindow().getTo() == null) {
            throw exception(FUNNEL_TIME_WINDOW_MISSING, "cohortWindow 未解析");
        }
        List<String> steps = params.getSteps();

        List<OntologySnapshot.EventDef> events = new ArrayList<>();
        for (String step : steps) {
            OntologySnapshot.EventDef event = ontology.getEvents().stream()
                    .filter(candidate -> step.equals(candidate.getEventType()))
                    .findFirst()
                    .orElseThrow(() -> exception(FUNNEL_EVENT_NOT_FOUND, step));
            events.add(event);
        }
        // 整条 CTE 共用首事件的判定列/时间列/主体列；后续事件映射不同则拒绝，
        // 不能让 SQL 按首事件解读别的步骤（静默错数）。
        OntologySnapshot.EventDef first = events.get(0);
        for (OntologySnapshot.EventDef event : events) {
            if (!java.util.Objects.equals(first.getEventTypeProperty(), event.getEventTypeProperty())
                    || !java.util.Objects.equals(first.getTimeProperty(), event.getTimeProperty())
                    || !java.util.Objects.equals(first.getParticipantProperty(), event.getParticipantProperty())) {
                throw exception(FUNNEL_EVENT_MAPPING_MISSING,
                        "步骤事件物理映射不一致: " + event.getId());
            }
        }
        OntologySnapshot.Property typeProperty =
                requireEventProperty(ontology, events.get(0).getEventTypeProperty());
        OntologySnapshot.Property timeProperty = requireEventProperty(ontology, events.get(0).getTimeProperty());
        OntologySnapshot.Property participantProperty =
                requireEventProperty(ontology, events.get(0).getParticipantProperty());
        String eventTable = tableOf(timeProperty.getColumn());
        if (eventTable == null
                || !eventTable.equals(tableOf(typeProperty.getColumn()))
                || !eventTable.equals(tableOf(participantProperty.getColumn()))) {
            throw exception(FUNNEL_EVENT_MAPPING_MISSING, "事件映射属性跨表或缺少表限定");
        }

        Field<Timestamp> eventTime = DSL.field(columnName(timeProperty.getColumn()), Timestamp.class);
        Field<String> eventType = DSL.field(columnName(typeProperty.getColumn()), String.class);
        Field<String> participant = DSL.field(columnName(participantProperty.getColumn()), String.class);

        Timestamp cohortFrom = shanghaiTimestamp(params.getCohortWindow().getFrom());
        Timestamp cohortTo = shanghaiTimestamp(params.getCohortWindow().getTo());
        String stepInterval = toInterval(params.getStepWindow());

        OntologySnapshot.Property subject = ontology.propertyIndex().get(params.getSubjectPropertyId());
        if (subject == null || tableOf(subject.getColumn()) == null) {
            throw exception(FUNNEL_EVENT_MAPPING_MISSING,
                    "subjectPropertyId 无法解析物理列: " + params.getSubjectPropertyId());
        }
        String subjectTable = tableOf(subject.getColumn());
        List<String> dimensionIds = queryDsl.getDimensions() == null
                ? List.of() : queryDsl.getDimensions();
        List<SemanticManifest.Dimension> dimensions = new ArrayList<>();
        for (String dimensionId : dimensionIds) {
            dimensions.add(semantic.requireDimension(dimensionId));
        }
        // M6/T1.5：顶层 filters = 全局过滤——cohort 与每个步进 CTE 共享同一组条件
        // （只作用于主体维度；时间维度在订单/事件表，校验器已按「必须映射主体表」拒绝）
        List<Condition> filters = new ArrayList<>();
        for (QueryDsl.Filter filter : queryDsl.getFilters() == null
                ? List.<QueryDsl.Filter>of() : queryDsl.getFilters()) {
            filters.add(condition(filter, semantic.requireDimension(filter.getFieldId())));
        }

        // cohort：首步事件 + 首次发生时间 + 首次触点维度值
        SelectQuery<Record> cohort = dsl.selectQuery();
        cohort.addSelect(participant.as("subject"));
        cohort.addSelect(DSL.min(eventTime).as("first_time"));
        for (SemanticManifest.Dimension dimension : dimensions) {
            cohort.addSelect(DSL.field(assetColumn(dimension.getTable(), dimension.getColumn()))
                    .as(DSL.name(dimension.getId())));
        }
        cohort.addFrom(DSL.table(tableName(eventTable)));
        if (!dimensions.isEmpty() || !filters.isEmpty()) {
            cohort.addJoin(DSL.table(tableName(subjectTable)),
                    DSL.field(columnName(subject.getColumn())).eq(participant));
        }
        cohort.addConditions(
                eventType.eq(events.get(0).getEventTypeValue()),
                eventTime.ge(DSL.val(cohortFrom)),
                eventTime.le(DSL.val(cohortTo)));
        cohort.addConditions(filters);
        List<Field<?>> cohortGroup = new ArrayList<>();
        cohortGroup.add(participant);
        for (SemanticManifest.Dimension dimension : dimensions) {
            cohortGroup.add(DSL.field(assetColumn(dimension.getTable(), dimension.getColumn())));
        }
        cohort.addGroupBy(cohortGroup);

        // step_2..step_N：严格有序 + 步窗约束
        List<org.jooq.CommonTableExpression<Record>> ctes = new ArrayList<>();
        ctes.add(DSL.name("cohort").as(cohort));
        String previousCte = "cohort";
        Field<Timestamp> previousTime = DSL.field(DSL.name("cohort", "first_time"), Timestamp.class);
        for (int index = 1; index < events.size(); index++) {
            String cteName = "step_" + (index + 1);
            OntologySnapshot.EventDef event = events.get(index);
            SelectQuery<Record> step = dsl.selectQuery();
            step.addSelect(participant.as("subject"));
            step.addSelect(DSL.min(eventTime).as("step_time"));
            step.addFrom(DSL.table(tableName(eventTable)));
            step.addJoin(DSL.table(DSL.name(previousCte)),
                    DSL.field(DSL.name(previousCte, "subject")).eq(participant));
            if (!filters.isEmpty()) {
                // 全局过滤：每个步进 CTE 共享同一组主体维度条件（规格 §3），
                // 只在 cohort 过滤会让后续步骤把「被过滤掉主体的行为」算回转化
                step.addJoin(DSL.table(tableName(subjectTable)),
                        DSL.field(columnName(subject.getColumn())).eq(participant));
            }
            step.addConditions(
                    eventType.eq(event.getEventTypeValue()),
                    eventTime.gt(previousTime),
                    eventTime.le(DSL.field("cast({0} as timestamp) + cast({1} as interval)",
                            Timestamp.class, previousTime, DSL.inline(stepInterval))));
            step.addConditions(filters);
            step.addGroupBy(participant);
            ctes.add(DSL.name(cteName).as(step));
            previousCte = cteName;
            previousTime = DSL.field(DSL.name(previousCte, "step_time"), Timestamp.class);
        }

        // 聚合：每步 count(distinct subject) + 维度分组
        SelectQuery<Record> aggregate = dsl.selectQuery();
        for (SemanticManifest.Dimension dimension : dimensions) {
            aggregate.addSelect(DSL.field(DSL.name("cohort", dimension.getId()))
                    .as(DSL.name(dimension.getId())));
        }
        aggregate.addSelect(DSL.countDistinct(DSL.field(DSL.name("cohort", "subject"))).as("step_1"));
        for (int index = 1; index < events.size(); index++) {
            String cteName = "step_" + (index + 1);
            aggregate.addSelect(DSL.countDistinct(DSL.field(DSL.name(cteName, "subject")))
                    .as("step_" + (index + 1)));
        }
        aggregate.addFrom(DSL.table(DSL.name("cohort")));
        for (int index = 1; index < events.size(); index++) {
            String cteName = "step_" + (index + 1);
            aggregate.addJoin(DSL.table(DSL.name(cteName)), org.jooq.JoinType.LEFT_OUTER_JOIN,
                    DSL.field(DSL.name(cteName, "subject"))
                            .eq(DSL.field(DSL.name("cohort", "subject"))));
        }
        if (!dimensions.isEmpty()) {
            List<Field<?>> groupBy = new ArrayList<>();
            for (SemanticManifest.Dimension dimension : dimensions) {
                groupBy.add(DSL.field(DSL.name("cohort", dimension.getId())));
            }
            aggregate.addGroupBy(groupBy);
        }

        // 外层：转化率（分母 = 上一步）。CTE 只能通过 WithStep 链式声明，
        // SelectQuery 本身没有 addWith（jOOQ 3.21）。
        List<Field<?>> outerFields = new ArrayList<>();
        for (SemanticManifest.Dimension dimension : dimensions) {
            outerFields.add(DSL.field(DSL.name("q", dimension.getId()))
                    .as(DSL.name(dimension.getId())));
        }
        for (int index = 1; index <= events.size(); index++) {
            outerFields.add(DSL.field(DSL.name("q", "step_" + index)).as("step_" + index));
        }
        for (int index = 2; index <= events.size(); index++) {
            Field<?> current = DSL.field(DSL.name("q", "step_" + index));
            Field<?> prior = DSL.field(DSL.name("q", "step_" + (index - 1)));
            // {0} 当前步、{1} 上一步；模板索引必须与参数顺序一一对应，
            // 写成两个 {0} 会让分母也取当前步（转化率恒为 1）
            outerFields.add(DSL.field("cast({0} as numeric) / nullif({1}, 0)", BigDecimal.class,
                    current, prior).as("conversion_" + index));
        }
        List<Field<?>> orderFields = new ArrayList<>();
        for (SemanticManifest.Dimension dimension : dimensions) {
            orderFields.add(DSL.field(DSL.name("q", dimension.getId())));
        }
        org.jooq.SelectJoinStep<Record> from = dsl.with(ctes)
                .select(outerFields.toArray(new Field[0]))
                .from(DSL.table(aggregate).as("q"));
        int limit = queryDsl.getLimit() == null ? 500 : queryDsl.getLimit();
        org.jooq.Select<Record> outer = orderFields.isEmpty()
                ? from.limit(limit)
                : from.orderBy(orderFields.toArray(new Field[0])).limit(limit);

        Query query = outer;
        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId(manifest.getManifestId());
        compiled.setContentHash(manifest.getContentHash());
        compiled.setMode(QueryDsl.AnalysisMode.FUNNEL.name());
        compiled.setDimensions(dimensionIds);
        compiled.setJoins(List.of());
        compiled.setDialect("postgresql");
        compiled.setSql(query.getSQL(ParamType.INDEXED));
        compiled.setBindValues(query.getBindValues());
        compiled.setAppliedLimit(limit);
        compiled.setCompiledAt(Instant.now());
        return compiled;
    }

    /**
     * retention 留存构造器（M6/T1.3，模式规格 v1.1 §1）。
     *
     * <p>口径：cohort = 主体在观察窗内的首次 cohort 事件所在周期（自然周 / 自然月，
     * 左闭右开）；retention[period_index] = 该周期内发生过 return 事件的主体数（distinct）。
     * 未完整结束的周期整期不产出（不冒充完整数据）；窗内没有任何完整 cohort 期时整题拒绝。
     * 维度只取主体表，事件级维度首发不支持（校验器已拒绝，编译器按同一口径纵深防御）。
     */
    private CompiledQuery compileRetention(QueryDsl queryDsl, PublishedManifest manifest) {
        OntologySnapshot ontology = manifest.getOntology();
        SemanticManifest semantic = manifest.getSemantic();
        QueryDsl.AnalysisParams params = queryDsl.getAnalysisParams();
        if (params == null || params.getCohortEvent() == null || params.getReturnEvent() == null) {
            throw exception(DSL_INVALID, "retention 缺少 cohortEvent / returnEvent");
        }
        QueryDsl.TimeRange range = queryDsl.getTimeRange();
        if (range == null || range.getFrom() == null || range.getTo() == null) {
            throw exception(DSL_INVALID, "retention 缺少已解析的 timeRange");
        }
        String unit = retentionUnit(params.getPeriodUnit());
        int periods = params.getPeriods() == null ? 0 : params.getPeriods();
        if (periods < 2 || periods > 12) {
            throw exception(DSL_INVALID, "retention periods 必须在 [2, 12]");
        }
        OntologySnapshot.EventDef cohortEvent = retentionEvent(ontology, params.getCohortEvent());
        OntologySnapshot.EventDef returnEvent = retentionEvent(ontology, params.getReturnEvent());
        if (!sameEventMapping(cohortEvent, returnEvent)) {
            throw exception(DSL_INVALID, "retention cohort / return 事件物理映射不一致");
        }
        OntologySnapshot.Property typeProperty = eventProperty(ontology, cohortEvent.getEventTypeProperty());
        OntologySnapshot.Property timeProperty = eventProperty(ontology, cohortEvent.getTimeProperty());
        OntologySnapshot.Property participantProperty =
                eventProperty(ontology, cohortEvent.getParticipantProperty());
        String eventTable = tableOf(timeProperty.getColumn());
        if (eventTable == null
                || !eventTable.equals(tableOf(typeProperty.getColumn()))
                || !eventTable.equals(tableOf(participantProperty.getColumn()))) {
            throw exception(DSL_INVALID, "retention 事件映射属性跨表或缺少表限定");
        }
        OntologySnapshot.Property subject = ontology.propertyIndex().get(params.getSubjectPropertyId());
        if (subject == null || tableOf(subject.getColumn()) == null) {
            throw exception(DSL_INVALID,
                    "retention subjectPropertyId 无法解析物理列: " + params.getSubjectPropertyId());
        }
        String subjectTable = tableOf(subject.getColumn());
        List<String> dimensionIds = queryDsl.getDimensions() == null ? List.of() : queryDsl.getDimensions();
        List<SemanticManifest.Dimension> dimensions = new ArrayList<>();
        for (String dimensionId : dimensionIds) {
            dimensions.add(semantic.requireDimension(dimensionId));
        }

        Field<Timestamp> eventTime = DSL.field(columnName(timeProperty.getColumn()), Timestamp.class);
        Field<String> eventType = DSL.field(columnName(typeProperty.getColumn()), String.class);
        Field<String> participant = DSL.field(columnName(participantProperty.getColumn()), String.class);

        // 完整周期判定：以上海「今日」为观察锚点，最后一个完整周期是当前周期的前一周期
        LocalDate today = LocalDate.now(clock);
        LocalDate windowStart = range.getFrom()
                .atZone(ZoneId.of(TimeExpressionParser.ZONE)).toLocalDate();
        LocalDate latestComplete = lastCompletePeriodStart(today, unit);
        LocalDate firstPeriod = retentionPeriodStart(windowStart, unit);
        if (firstPeriod.isAfter(latestComplete)) {
            throw exception(RETENTION_PERIOD_INCOMPLETE,
                    "观察窗起点 " + firstPeriod + " 晚于最后完整周期 " + latestComplete);
        }
        Timestamp anchor = Timestamp.valueOf(today.atStartOfDay());

        // cohort_first：窗内首次 cohort 事件（按主体聚合，min 已覆盖「同时刻取一」的确定性）
        SelectQuery<Record> first = dsl.selectQuery();
        first.addSelect(participant.as("subject"));
        first.addSelect(DSL.min(eventTime).as("first_time"));
        first.addFrom(DSL.table(tableName(eventTable)));
        first.addConditions(
                eventType.eq(cohortEvent.getEventTypeValue()),
                eventTime.ge(DSL.val(shanghaiTimestamp(range.getFrom()))),
                eventTime.lt(DSL.val(shanghaiTimestamp(range.getTo()))));
        first.addGroupBy(participant);

        // cohort：首次事件所在周期 + cohort 时点的主体维度（主体表维度对同一主体唯一）
        Field<String> firstSubject = DSL.field(DSL.name("cohort_first", "subject"), String.class);
        Field<Timestamp> firstTime = DSL.field(DSL.name("cohort_first", "first_time"), Timestamp.class);
        Field<Timestamp> bucket = DSL.field("date_trunc({0}, {1})", Timestamp.class,
                DSL.inline(unit), firstTime);
        SelectQuery<Record> cohort = dsl.selectQuery();
        cohort.addSelect(firstSubject.as("subject"));
        cohort.addSelect(bucket.as("cohort_period"));
        for (SemanticManifest.Dimension dimension : dimensions) {
            cohort.addSelect(DSL.field(assetColumn(dimension.getTable(), dimension.getColumn()))
                    .as(DSL.name(dimension.getId())));
        }
        cohort.addFrom(DSL.table(DSL.name("cohort_first")));
        if (!dimensions.isEmpty()) {
            cohort.addJoin(DSL.table(tableName(subjectTable)),
                    DSL.field(columnName(subject.getColumn())).eq(firstSubject));
        }
        List<Field<?>> cohortGroup = new ArrayList<>();
        cohortGroup.add(firstSubject);
        cohortGroup.add(bucket);
        for (SemanticManifest.Dimension dimension : dimensions) {
            cohortGroup.add(DSL.field(assetColumn(dimension.getTable(), dimension.getColumn())));
        }
        cohort.addGroupBy(cohortGroup);

        // periods：0..periods-1 的观察期序号（生成序列先例见 yoy_mom 周期骨架）
        SelectQuery<Record> periodsQuery = dsl.selectQuery();
        periodsQuery.addSelect(DSL.field("generate_series({0}, {1})", Integer.class,
                DSL.val(0), DSL.val(periods - 1)).as("period_index"));

        // retained：cohort × periods 逐期判定 return 事件；未完整期整期不产出
        Field<String> cohortSubject = DSL.field(DSL.name("c", "subject"), String.class);
        Field<Timestamp> cohortPeriod = DSL.field(DSL.name("c", "cohort_period"), Timestamp.class);
        Field<Integer> periodIndex = DSL.field(DSL.name("p", "period_index"), Integer.class);
        // interval 字面量来自封闭枚举（week / month），不是用户输入
        Field<Timestamp> periodStart = DSL.field("{0} + ({1} * cast({2} as interval))",
                Timestamp.class, cohortPeriod, periodIndex, DSL.inline("1 " + unit));
        Field<Timestamp> periodEnd = DSL.field("{0} + (({1} + 1) * cast({2} as interval))",
                Timestamp.class, cohortPeriod, periodIndex, DSL.inline("1 " + unit));
        Condition returned = DSL.exists(dsl.selectOne()
                .from(DSL.table(tableName(eventTable)))
                .where(participant.eq(cohortSubject),
                        eventType.eq(returnEvent.getEventTypeValue()),
                        eventTime.ge(periodStart),
                        eventTime.lt(periodEnd)));
        SelectQuery<Record> retained = dsl.selectQuery();
        retained.addSelect(cohortSubject.as("subject"));
        retained.addSelect(cohortPeriod.as("cohort_period"));
        retained.addSelect(periodIndex.as("period_index"));
        for (SemanticManifest.Dimension dimension : dimensions) {
            retained.addSelect(DSL.field(DSL.name("c", dimension.getId())).as(DSL.name(dimension.getId())));
        }
        retained.addSelect(DSL.field("case when {0} then 1 else 0 end", Integer.class, returned)
                .as("retained"));
        retained.addFrom(DSL.table(DSL.name("cohort")).as("c"));
        retained.addFrom(DSL.table(periodsQuery).as("p"));
        retained.addConditions(periodEnd.le(DSL.val(anchor)));

        // 外层聚合：cohort_users / retained_users / retention_rate（分母为 0 时 null，无除零路径）
        Field<String> outSubject = DSL.field(DSL.name("r", "subject"), String.class);
        Field<Integer> outRetained = DSL.field(DSL.name("r", "retained"), Integer.class);
        Field<Integer> cohortUsers = DSL.countDistinct(outSubject);
        Field<Integer> retainedUsers = DSL.countDistinct(outSubject)
                .filterWhere(outRetained.eq(1));
        List<Field<?>> outerFields = new ArrayList<>();
        outerFields.add(DSL.field(DSL.name("r", "cohort_period")).as("cohort_period"));
        outerFields.add(DSL.field(DSL.name("r", "period_index")).as("period_index"));
        for (SemanticManifest.Dimension dimension : dimensions) {
            outerFields.add(DSL.field(DSL.name("r", dimension.getId())).as(DSL.name(dimension.getId())));
        }
        outerFields.add(cohortUsers.as("cohort_users"));
        outerFields.add(retainedUsers.as("retained_users"));
        outerFields.add(DSL.field("cast({0} as numeric) / nullif({1}, 0)", BigDecimal.class,
                retainedUsers, cohortUsers).as("retention_rate"));

        List<Field<?>> groupFields = new ArrayList<>();
        List<Field<?>> orderFields = new ArrayList<>();
        groupFields.add(DSL.field(DSL.name("r", "cohort_period")));
        groupFields.add(DSL.field(DSL.name("r", "period_index")));
        orderFields.add(DSL.field(DSL.name("r", "cohort_period")));
        orderFields.add(DSL.field(DSL.name("r", "period_index")));
        for (SemanticManifest.Dimension dimension : dimensions) {
            groupFields.add(DSL.field(DSL.name("r", dimension.getId())));
            orderFields.add(DSL.field(DSL.name("r", dimension.getId())));
        }

        List<CommonTableExpression<Record>> ctes = new ArrayList<>();
        ctes.add(DSL.name("cohort_first").as(first));
        ctes.add(DSL.name("cohort").as(cohort));
        ctes.add(DSL.name("retained").as(retained));
        int limit = queryDsl.getLimit() == null ? 500 : queryDsl.getLimit();
        Query query = dsl.with(ctes)
                .select(outerFields.toArray(new Field[0]))
                .from(DSL.table(DSL.name("retained")).as("r"))
                .groupBy(groupFields)
                .orderBy(orderFields)
                .limit(limit);

        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId(manifest.getManifestId());
        compiled.setContentHash(manifest.getContentHash());
        compiled.setMode(QueryDsl.AnalysisMode.RETENTION.name());
        compiled.setDimensions(dimensionIds);
        compiled.setJoins(List.of());
        compiled.setDialect("postgresql");
        compiled.setSql(query.getSQL(ParamType.INDEXED));
        compiled.setBindValues(query.getBindValues());
        compiled.setAppliedLimit(limit);
        compiled.setCompiledAt(Instant.now());
        return compiled;
    }

    /** WEEK | MONTH → PostgreSQL date_trunc 单位（封闭枚举由校验器保证，编译器不猜测）。 */
    private static String retentionUnit(String periodUnit) {
        if ("WEEK".equals(periodUnit)) {
            return "week";
        }
        if ("MONTH".equals(periodUnit)) {
            return "month";
        }
        throw exception(DSL_INVALID, "retention periodUnit 仅允许 WEEK / MONTH: " + periodUnit);
    }

    private static OntologySnapshot.EventDef retentionEvent(OntologySnapshot ontology, String eventType) {
        List<OntologySnapshot.EventDef> matched = ontology.getEvents().stream()
                .filter(event -> eventType != null && eventType.equals(event.getEventType()))
                .toList();
        if (matched.size() != 1) {
            throw exception(RETENTION_EVENT_NOT_FOUND, String.valueOf(eventType));
        }
        return matched.get(0);
    }

    private static OntologySnapshot.Property eventProperty(OntologySnapshot ontology, String propertyId) {
        OntologySnapshot.Property property = propertyId == null ? null : ontology.propertyIndex().get(propertyId);
        if (property == null) {
            throw exception(DSL_INVALID, "retention 事件映射属性未发布: " + propertyId);
        }
        return property;
    }

    private static boolean sameEventMapping(OntologySnapshot.EventDef left, OntologySnapshot.EventDef right) {
        return java.util.Objects.equals(left.getEventTypeProperty(), right.getEventTypeProperty())
                && java.util.Objects.equals(left.getTimeProperty(), right.getTimeProperty())
                && java.util.Objects.equals(left.getParticipantProperty(), right.getParticipantProperty());
    }

    /** 周期起点：WEEK 取周一（周一起），MONTH 取每月 1 日（ADR-0004 §2.4）。 */
    private static LocalDate retentionPeriodStart(LocalDate date, String unit) {
        return "week".equals(unit)
                ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : date.withDayOfMonth(1);
    }

    /** 最后一个已完整结束的周期起点：当前周期尚未结束，取前一周期（不冒充完整数据）。 */
    private static LocalDate lastCompletePeriodStart(LocalDate today, String unit) {
        LocalDate currentStart = retentionPeriodStart(today, unit);
        return "week".equals(unit) ? currentStart.minusWeeks(1) : currentStart.minusMonths(1);
    }

    /**
     * attribution 简单归因构造器（M6/T1.4，模式规格 v1.1 §2）：末次触点 + 显式 UNATTRIBUTED 桶。
     *
     * <p>口径：逐笔转化事件独立归因；回溯窗口 `[转化时刻 - window, 转化时刻)` 左闭右开；
     * 窗口内 `(occurred_at, dedup_key)` 双降序取首行触点的渠道获得该笔转化 100% 权重；
     * 无触点转化归入 `UNATTRIBUTED`（不丢弃、不冒泡）；无主体的转化/触点排除。
     * 指标值经清单关系（指标表 ↔ 事件表）结构化 Join 得到，物理列全部来自已发布清单。
     */
    private CompiledQuery compileAttribution(QueryDsl queryDsl, PublishedManifest manifest) {
        OntologySnapshot ontology = manifest.getOntology();
        SemanticManifest semantic = manifest.getSemantic();
        QueryDsl.AnalysisParams params = queryDsl.getAnalysisParams();
        if (params == null || params.getConversionEvent() == null || params.getTouchEvent() == null) {
            throw exception(DSL_INVALID, "attribution 缺少 conversionEvent / touchEvent");
        }
        QueryDsl.TimeRange range = queryDsl.getTimeRange();
        if (range == null || range.getFrom() == null || range.getTo() == null) {
            throw exception(DSL_INVALID, "attribution 缺少已解析的 timeRange");
        }
        SemanticManifest.Metric metric = semantic.requireMetric(queryDsl.getMetricId());
        if (!metric.getAllowedModes().contains(QueryDsl.AnalysisMode.ATTRIBUTION.name())) {
            throw exception(DSL_INVALID, "指标未开放 ATTRIBUTION: " + metric.getId());
        }
        String windowInterval = attributionInterval(params.getAttributionWindow());

        OntologySnapshot.EventDef conversionEvent = attributionEvent(ontology, params.getConversionEvent());
        OntologySnapshot.EventDef touchEvent = attributionEvent(ontology, params.getTouchEvent());
        OntologySnapshot.Property convType =
                attributionProperty(ontology, conversionEvent.getEventTypeProperty());
        OntologySnapshot.Property convTime =
                attributionProperty(ontology, conversionEvent.getTimeProperty());
        OntologySnapshot.Property convParticipant =
                attributionProperty(ontology, conversionEvent.getParticipantProperty());
        OntologySnapshot.Property touchType = attributionProperty(ontology, touchEvent.getEventTypeProperty());
        OntologySnapshot.Property touchTime = attributionProperty(ontology, touchEvent.getTimeProperty());
        OntologySnapshot.Property touchParticipant =
                attributionProperty(ontology, touchEvent.getParticipantProperty());
        OntologySnapshot.Property touchChannel =
                attributionProperty(ontology, touchEvent.getChannelPropertyId());
        OntologySnapshot.Property eventId = attributionProperty(ontology, EVENT_ID_PROPERTY_ID);
        OntologySnapshot.Property eventDedup = attributionProperty(ontology, EVENT_DEDUP_KEY_PROPERTY_ID);
        OntologySnapshot.Property channelName = attributionProperty(ontology, CHANNEL_NAME_PROPERTY_ID);
        OntologySnapshot.Property channelId = attributionProperty(ontology, CHANNEL_ID_PROPERTY_ID);
        String eventTable = tableOf(convTime.getColumn());
        if (eventTable == null
                || !eventTable.equals(tableOf(touchTime.getColumn()))
                || !eventTable.equals(tableOf(convType.getColumn()))
                || !eventTable.equals(tableOf(convParticipant.getColumn()))
                || !eventTable.equals(tableOf(touchType.getColumn()))
                || !eventTable.equals(tableOf(touchParticipant.getColumn()))
                || !eventTable.equals(tableOf(touchChannel.getColumn()))
                || !eventTable.equals(tableOf(eventId.getColumn()))
                || !eventTable.equals(tableOf(eventDedup.getColumn()))) {
            throw exception(DSL_INVALID, "attribution 转化 / 触点事件映射未落在同一物理表");
        }
        OntologySnapshot.Relation relation =
                requireMetricEventRelation(ontology, metric.getTable(), eventTable);
        boolean metricSideIsFrom = metric.getTable().equals(tableOf(relation.getFromColumn()));
        String metricKeyColumn = metricSideIsFrom ? relation.getFromColumn() : relation.getToColumn();
        String eventKeyColumn = metricSideIsFrom ? relation.getToColumn() : relation.getFromColumn();

        List<String> dimensionIds = queryDsl.getDimensions() == null ? List.of() : queryDsl.getDimensions();
        List<SemanticManifest.Dimension> dimensions = new ArrayList<>();
        boolean channelDimension = false;
        for (String dimensionId : dimensionIds) {
            SemanticManifest.Dimension dimension = semantic.requireDimension(dimensionId);
            dimensions.add(dimension);
            channelDimension |= CHANNEL_DIMENSION_ID.equals(dimensionId);
        }

        // 外层事件限定列（别名 e）与触点子查询限定列（别名 t），逐列限定避免自连接歧义
        Field<String> eId = DSL.field(DSL.name("e", lastSegment(eventId.getColumn())), String.class);
        Field<String> eOrder = DSL.field(DSL.name("e", lastSegment(eventKeyColumn)), String.class);
        Field<Timestamp> eTime = DSL.field(DSL.name("e", lastSegment(convTime.getColumn())), Timestamp.class);
        Field<String> eType = DSL.field(DSL.name("e", lastSegment(convType.getColumn())), String.class);
        Field<String> eParticipant =
                DSL.field(DSL.name("e", lastSegment(convParticipant.getColumn())), String.class);
        Field<String> tType = DSL.field(DSL.name("t", lastSegment(touchType.getColumn())), String.class);
        Field<Timestamp> tTime = DSL.field(DSL.name("t", lastSegment(touchTime.getColumn())), Timestamp.class);
        Field<String> tParticipant =
                DSL.field(DSL.name("t", lastSegment(touchParticipant.getColumn())), String.class);
        Field<String> tChannel =
                DSL.field(DSL.name("t", lastSegment(touchChannel.getColumn())), String.class);
        Field<String> tDedup =
                DSL.field(DSL.name("t", lastSegment(eventDedup.getColumn())), String.class);

        // last_touch：窗口内最近触点（同一时刻按 dedup_key 降序取一，全序显式、同输入同输出）
        var lastTouch = dsl.select(tChannel)
                .from(DSL.table(tableName(eventTable)).as("t"))
                .where(tParticipant.eq(eParticipant),
                        tType.eq(touchEvent.getEventTypeValue()),
                        tChannel.isNotNull(),
                        tTime.ge(DSL.field("{0} - cast({1} as interval)", Timestamp.class,
                                eTime, DSL.inline(windowInterval))),
                        tTime.lt(eTime))
                .orderBy(tTime.desc(), tDedup.desc())
                .limit(1);

        SelectQuery<Record> conversions = dsl.selectQuery();
        conversions.addSelect(eId.as("conversion_id"));
        conversions.addSelect(eOrder.as("order_id"));
        conversions.addSelect(DSL.field(lastTouch).as("attributed_channel_id"));
        conversions.addFrom(DSL.table(tableName(eventTable)).as("e"));
        conversions.addConditions(
                eType.eq(conversionEvent.getEventTypeValue()),
                eParticipant.isNotNull(),
                eTime.ge(DSL.val(shanghaiTimestamp(range.getFrom()))),
                eTime.lt(DSL.val(shanghaiTimestamp(range.getTo()))));

        // 渠道名映射：触点渠道 id → channel.name（渠道维度值来源；禁止自由文本渠道）
        // 注意：JOIN 后表只能用别名 ch 限定——沿用未限定的 channel.* 会撞 missing FROM-clause
        Field<String> chId = DSL.field(DSL.name("ch", lastSegment(channelId.getColumn())), String.class);
        Field<String> chName = DSL.field(DSL.name("ch", lastSegment(channelName.getColumn())), String.class);
        SelectQuery<Record> attributed = dsl.selectQuery();
        attributed.addSelect(DSL.field(DSL.name("c", "conversion_id")).as("conversion_id"));
        attributed.addSelect(DSL.field(DSL.name("c", "order_id")).as("order_id"));
        attributed.addSelect(DSL.field(DSL.name("c", "attributed_channel_id"), String.class)
                .as("attributed_channel_id"));
        attributed.addSelect(chName.as("channel_name"));
        attributed.addFrom(DSL.table(DSL.name("conversions")).as("c"));
        attributed.addJoin(DSL.table(tableName(tableOf(channelName.getColumn()))).as("ch"),
                org.jooq.JoinType.LEFT_OUTER_JOIN,
                chId.eq(DSL.field(DSL.name("c", "attributed_channel_id"), String.class)));

        // facts：转化经清单关系 Join 指标表（指标值与订单维度同源，一转化一行）
        SelectQuery<Record> facts = dsl.selectQuery();
        facts.addSelect(DSL.field(DSL.name("a", "conversion_id")).as("conversion_id"));
        facts.addSelect(DSL.field(DSL.name("a", "attributed_channel_id"), String.class)
                .as("attributed_channel_id"));
        facts.addSelect(DSL.field(DSL.name("a", "channel_name")).as("channel_name"));
        facts.addSelect(DSL.field(DSL.name("o", lastSegment(metric.getColumn()))).as("metric_value"));
        for (SemanticManifest.Dimension dimension : dimensions) {
            if (CHANNEL_DIMENSION_ID.equals(dimension.getId())) {
                continue;
            }
            facts.addSelect(DSL.field(DSL.name("o", lastSegment(dimension.getColumn())))
                    .as(DSL.name(dimension.getId())));
        }
        facts.addFrom(DSL.table(DSL.name("attributed")).as("a"));
        facts.addJoin(DSL.table(tableName(metric.getTable())).as("o"), org.jooq.JoinType.JOIN,
                DSL.field(DSL.name("o", lastSegment(metricKeyColumn)))
                        .eq(DSL.field(DSL.name("a", "order_id"))));

        // 外层聚合：渠道（UNATTRIBUTED 显式桶）+ 订单维度 + 指标值 + 转化数 / 命中触点数
        Field<String> outConversionId = DSL.field(DSL.name("f", "conversion_id"), String.class);
        Field<String> outChannelName = DSL.field(DSL.name("f", "channel_name"), String.class);
        Field<String> outChannelId = DSL.field(DSL.name("f", "attributed_channel_id"), String.class);
        Field<Object> outMetricValue = DSL.field(DSL.name("f", "metric_value"));
        // UNATTRIBUTED 只收「已知主体、无触点」的转化（规格 §2.2）；有触点但渠道主数据缺失时
        // 回退显示渠道 id，不能伪装成无触点（否则 touched_events 与桶语义同时错位）
        Field<String> channelBucket = DSL.field(
                "case when {0} is null then 'UNATTRIBUTED' else coalesce({1}, {0}) end",
                String.class, outChannelId, outChannelName);
        List<Field<?>> outerFields = new ArrayList<>();
        List<Field<?>> groupFields = new ArrayList<>();
        List<Field<?>> orderFields = new ArrayList<>();
        if (channelDimension) {
            outerFields.add(channelBucket.as(DSL.name(CHANNEL_DIMENSION_ID)));
            groupFields.add(channelBucket);
            orderFields.add(channelBucket);
        }
        for (SemanticManifest.Dimension dimension : dimensions) {
            if (CHANNEL_DIMENSION_ID.equals(dimension.getId())) {
                continue;
            }
            Field<?> field = DSL.field(DSL.name("f", dimension.getId()));
            outerFields.add(field.as(DSL.name(dimension.getId())));
            groupFields.add(field);
            orderFields.add(field);
        }
        outerFields.add(attributionAggregate(metric, outMetricValue).as("attributed_metric_value"));
        outerFields.add(DSL.countDistinct(outConversionId).as("converted_events"));
        outerFields.add(DSL.field(
                "count(distinct case when {0} is not null then {1} end)", Integer.class,
                outChannelId, outConversionId).as("touched_events"));

        List<CommonTableExpression<Record>> ctes = new ArrayList<>();
        ctes.add(DSL.name("conversions").as(conversions));
        ctes.add(DSL.name("attributed").as(attributed));
        ctes.add(DSL.name("facts").as(facts));
        int limit = queryDsl.getLimit() == null ? 500 : queryDsl.getLimit();
        org.jooq.Select<? extends Record> query = dsl.with(ctes)
                .select(outerFields.toArray(new Field[0]))
                .from(DSL.table(DSL.name("facts")).as("f"))
                .groupBy(groupFields)
                .orderBy(orderFields)
                .limit(limit);

        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId(manifest.getManifestId());
        compiled.setContentHash(manifest.getContentHash());
        compiled.setMode(QueryDsl.AnalysisMode.ATTRIBUTION.name());
        compiled.setMetricId(metric.getId());
        compiled.setDimensions(dimensionIds);
        compiled.setJoins(List.of());
        compiled.setDialect("postgresql");
        compiled.setSql(query.getSQL(ParamType.INDEXED));
        compiled.setBindValues(query.getBindValues());
        compiled.setAppliedLimit(limit);
        compiled.setCompiledAt(Instant.now());
        return compiled;
    }

    /**
     * 指标表 ↔ 事件表的关联关系（物理列来自已发布 Relation，不按命名习惯猜 Join）。
     * 缺失或并列一律拒绝：归因指标值必须能确定性地落回转化订单。
     */
    private static OntologySnapshot.Relation requireMetricEventRelation(OntologySnapshot ontology,
                                                                       String metricTable, String eventTable) {
        List<OntologySnapshot.Relation> matched = ontology.getRelations().stream()
                .filter(OntologySnapshot.Relation::isQueryable)
                .filter(relation -> {
                    String from = tableOf(relation.getFromColumn());
                    String to = tableOf(relation.getToColumn());
                    return (metricTable.equals(from) && eventTable.equals(to))
                            || (eventTable.equals(from) && metricTable.equals(to));
                })
                .toList();
        if (matched.size() != 1) {
            throw exception(DSL_INVALID,
                    "attribution 需要唯一的指标表 ↔ 事件表关联关系，实际命中 " + matched.size());
        }
        return matched.get(0);
    }

    private static OntologySnapshot.EventDef attributionEvent(OntologySnapshot ontology, String eventType) {
        List<OntologySnapshot.EventDef> matched = ontology.getEvents().stream()
                .filter(event -> eventType != null && eventType.equals(event.getEventType()))
                .toList();
        if (matched.size() != 1) {
            throw exception(ATTRIBUTION_EVENT_NOT_FOUND, String.valueOf(eventType));
        }
        return matched.get(0);
    }

    private static OntologySnapshot.Property attributionProperty(OntologySnapshot ontology, String propertyId) {
        OntologySnapshot.Property property = propertyId == null ? null : ontology.propertyIndex().get(propertyId);
        if (property == null) {
            throw exception(ATTRIBUTION_EVENT_NOT_FOUND, "归因映射属性未发布: " + propertyId);
        }
        return property;
    }

    /** 触点回溯窗口封闭枚举 → PostgreSQL interval（值来自封闭枚举，不是用户输入）。 */
    private static String attributionInterval(String window) {
        if ("7d".equals(window)) {
            return "7 days";
        }
        if ("14d".equals(window)) {
            return "14 days";
        }
        if ("30d".equals(window)) {
            return "30 days";
        }
        throw exception(DSL_INVALID, "attribution attributionWindow 仅允许 7d / 14d / 30d: " + window);
    }

    /** 指标聚合落到归因事实行上（列已由 facts CTE 投影，这里只选聚合函数）。 */
    private static Field<?> attributionAggregate(SemanticManifest.Metric metric, Field<?> value) {
        if (metric.getAggregation() == null) {
            throw exception(DSL_INVALID, "指标缺少 aggregation: " + metric.getId());
        }
        Field<BigDecimal> decimalValue = value.coerce(BigDecimal.class);
        return switch (metric.getAggregation()) {
            case SUM -> DSL.sum(decimalValue);
            case AVG -> DSL.avg(decimalValue);
            case MIN -> DSL.min(decimalValue);
            case MAX -> DSL.max(decimalValue);
            case COUNT -> DSL.count();
            case COUNT_DISTINCT -> DSL.countDistinct(value);
        };
    }

    private static String lastSegment(String column) {
        int index = column == null ? -1 : column.lastIndexOf('.');
        return index < 0 ? column : column.substring(index + 1);
    }

    private static OntologySnapshot.Property requireEventProperty(OntologySnapshot ontology, String propertyId) {
        OntologySnapshot.Property property = propertyId == null
                ? null : ontology.propertyIndex().get(propertyId);
        if (property == null) {
            throw exception(FUNNEL_EVENT_MAPPING_MISSING, "属性未发布: " + propertyId);
        }
        return property;
    }

    /** step_window `7d` / `12h` / `30m` → PostgreSQL interval 字面量（格式由校验器保证）。 */
    private static String toInterval(String stepWindow) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(\\d+)([dhm])$").matcher(stepWindow == null ? "" : stepWindow);
        if (!matcher.matches()) {
            throw exception(FUNNEL_TIME_WINDOW_MISSING, "stepWindow=" + stepWindow);
        }
        String unit = switch (matcher.group(2)) {
            case "d" -> "days";
            case "h" -> "hours";
            default -> "minutes";
        };
        return matcher.group(1) + " " + unit;
    }

    /**
     * yoy_mom 同环比编译（analysis-mode 规格 v1 §2.3）。
     *
     * <p>先构建「周期骨架 × 维度枚举并集」，再左连聚合结果：缺失周期仍有行、指标值为 null，
     * lag 因此不会跨缺口错位。同比回看 12 个月 / 1 年，环比回看 1 期；相对值用
     * {@code nullif(prev, 0)} 安全除法，分母为零输出 null。当前不完整周期按保守口径整期排除，
     * 输出起点不早于本期 timeRange.from。
     */
    private CompiledQuery compileYoyMom(QueryDsl queryDsl, PublishedManifest manifest) {
        SemanticManifest semantic = manifest.getSemantic();
        SemanticManifest.Metric metric = semantic.requireMetric(queryDsl.getMetricId());
        QueryDsl.AnalysisParams params = queryDsl.getAnalysisParams();
        // 纵深防御：校验器已要求 analysisParams / period / timeRange，编译器不依赖调用顺序
        if (params == null || params.getPeriod() == null) {
            throw exception(DSL_INVALID, "yoy_mom 缺少 analysisParams.period");
        }
        boolean monthly = "MONTH".equals(params.getPeriod());
        String unit = monthly ? "month" : "year";
        int yoyLag = monthly ? 12 : 1;

        QueryDsl.TimeRange range = queryDsl.getTimeRange();
        if (range == null) {
            throw exception(DSL_INVALID, "yoy_mom 缺少 timeRange");
        }
        SemanticManifest.Dimension timeDimension = semantic.requireDimension(range.getField());
        List<String> dimensionIds = queryDsl.getDimensions() == null
                ? List.of() : queryDsl.getDimensions();
        List<SemanticManifest.Dimension> dimensions = new ArrayList<>();
        for (String dimensionId : dimensionIds) {
            dimensions.add(semantic.requireDimension(dimensionId));
        }
        List<QueryDsl.ResolvedJoin> joins = queryDsl.getResolvedJoins() == null
                ? List.of() : queryDsl.getResolvedJoins();
        // 过滤条件与跨表维度必须同时进入「聚合」与「维度枚举」两个 CTE：
        // 只进其中一个会让骨架与聚合口径分叉（多出来的维度值补 null、过滤后的维度值被漏掉）。
        List<Condition> filters = new ArrayList<>();
        for (QueryDsl.Filter filter : queryDsl.getFilters() == null
                ? List.<QueryDsl.Filter>of() : queryDsl.getFilters()) {
            filters.add(condition(filter, semantic.requireDimension(filter.getFieldId())));
        }
        Condition caliber = caliberCondition(semantic, metric, queryDsl);
        if (caliber != null) {
            filters.add(caliber);
        }

        Field<Timestamp> timeField = DSL.field(
                assetColumn(timeDimension.getTable(), timeDimension.getColumn()), Timestamp.class);
        // 周期边界一律按 Asia/Shanghai 的本地时间构造，不依赖 JVM 默认时区（ADR-0004 2.4）
        Timestamp from = shanghaiTimestamp(range.getFrom());
        Timestamp to = shanghaiTimestamp(range.getTo());
        // 同比回看需要把聚合范围扩到对比期，否则 yoy 永远为 null
        Timestamp windowFrom = shanghaiTimestamp(monthly
                ? range.getFrom().atZone(java.time.ZoneId.of(TimeExpressionParser.ZONE)).minusMonths(12).toInstant()
                : range.getFrom().atZone(java.time.ZoneId.of(TimeExpressionParser.ZONE)).minusYears(1).toInstant());

        Field<Timestamp> bucket = DSL.field("date_trunc({0}, {1})", Timestamp.class,
                DSL.inline(unit), timeField);
        List<Field<?>> rawDimensions = new ArrayList<>();
        for (SemanticManifest.Dimension dimension : dimensions) {
            rawDimensions.add(DSL.field(assetColumn(dimension.getTable(), dimension.getColumn())));
        }

        // 聚合 CTE：覆盖 [对比期起点, 本期终点]
        SelectQuery<Record> agg = dsl.selectQuery();
        agg.addSelect(bucket.as("period"));
        for (SemanticManifest.Dimension dimension : dimensions) {
            agg.addSelect(DSL.field(assetColumn(dimension.getTable(), dimension.getColumn()))
                    .as(DSL.name(dimension.getId())));
        }
        agg.addSelect(aggregate(metric).as("metric_value"));
        agg.addFrom(DSL.table(tableName(metric.getTable())));
        addJoins(agg, joins);
        agg.addConditions(timeField.ge(DSL.val(windowFrom)), timeField.le(DSL.val(to)));
        agg.addConditions(filters);
        List<Field<?>> groupBy = new ArrayList<>();
        groupBy.add(bucket);
        groupBy.addAll(rawDimensions);
        agg.addGroupBy(groupBy);

        // 周期骨架：到 to 所在周期的前一周期为止（当前不完整周期整期排除）
        SelectQuery<Record> periods = dsl.selectQuery();
        periods.addSelect(DSL.field(
                "generate_series(date_trunc({0}, {1}), date_trunc({0}, {2}) - cast({3} as interval), cast({3} as interval))",
                Timestamp.class, DSL.inline(unit), DSL.val(windowFrom), DSL.val(to),
                DSL.inline("1 " + unit)).as("period"));

        // 维度枚举并集（覆盖对比期，去年才出现的维度也要占位）
        SelectQuery<Record> dims = dsl.selectQuery();
        for (SemanticManifest.Dimension dimension : dimensions) {
            dims.addSelect(DSL.field(assetColumn(dimension.getTable(), dimension.getColumn()))
                    .as(DSL.name(dimension.getId())));
        }
        if (!dimensions.isEmpty()) {
            dims.addFrom(DSL.table(tableName(metric.getTable())));
            addJoins(dims, joins);
            dims.addConditions(timeField.ge(DSL.val(windowFrom)), timeField.le(DSL.val(to)));
            dims.addConditions(filters);
            dims.addGroupBy(rawDimensions);
        }

        // 骨架 = periods × dims（无维度时退化为纯周期序列）
        SelectQuery<Record> grid = dsl.selectQuery();
        grid.addSelect(DSL.field(DSL.name("p", "period"), Timestamp.class).as("period"));
        for (SemanticManifest.Dimension dimension : dimensions) {
            grid.addSelect(DSL.field(DSL.name("d", dimension.getId())).as(DSL.name(dimension.getId())));
        }
        grid.addFrom(DSL.table(periods).as("p"));
        if (!dimensions.isEmpty()) {
            grid.addFrom(DSL.table(dims).as("d"));
        }

        Field<Timestamp> periodField = DSL.field(DSL.name("g", "period"), Timestamp.class);
        Field<BigDecimal> value = DSL.field(DSL.name("a", "metric_value"), BigDecimal.class);
        org.jooq.WindowSpecification window = dimensions.isEmpty()
                ? DSL.orderBy(periodField)
                : DSL.partitionBy(gridDimensionFields(dimensions)).orderBy(periodField);
        Field<BigDecimal> previous = DSL.lag(value, 1).over(window);
        Field<BigDecimal> previousYoy = DSL.lag(value, yoyLag).over(window);

        // 窗口计算必须先于「本期起点」过滤：SQL 的 WHERE 在窗口函数之前执行，
        // 直接把 period >= from 写进同一层会把对比期行滤掉，导致同比恒为 null。
        SelectQuery<Record> windowed = dsl.selectQuery();
        windowed.addSelect(periodField.as("period"));
        for (SemanticManifest.Dimension dimension : dimensions) {
            windowed.addSelect(DSL.field(DSL.name("g", dimension.getId()))
                    .as(DSL.name(dimension.getId())));
        }
        windowed.addSelect(value.as("metric_value"));
        windowed.addSelect(DSL.field("({0} - {1}) / nullif({1}, 0)", BigDecimal.class, value, previous)
                .as("mom"));
        windowed.addSelect(DSL.field("({0} - {1}) / nullif({1}, 0)", BigDecimal.class, value, previousYoy)
                .as("yoy"));
        windowed.addFrom(DSL.table(grid).as("g"));
        Condition on = DSL.field(DSL.name("a", "period")).eq(periodField);
        for (SemanticManifest.Dimension dimension : dimensions) {
            on = on.and(DSL.field(DSL.name("a", dimension.getId()))
                    .eq(DSL.field(DSL.name("g", dimension.getId()))));
        }
        windowed.addJoin(DSL.table(agg).as("a"), org.jooq.JoinType.LEFT_OUTER_JOIN, on);

        Field<Timestamp> outputPeriod = DSL.field(DSL.name("w", "period"), Timestamp.class);
        SelectQuery<Record> main = dsl.selectQuery();
        main.addSelect(outputPeriod.as("period"));
        for (SemanticManifest.Dimension dimension : dimensions) {
            main.addSelect(DSL.field(DSL.name("w", dimension.getId()))
                    .as(DSL.name(dimension.getId())));
        }
        main.addSelect(DSL.field(DSL.name("w", "metric_value"), BigDecimal.class).as("metric_value"));
        main.addSelect(DSL.field(DSL.name("w", "mom"), BigDecimal.class).as("mom"));
        main.addSelect(DSL.field(DSL.name("w", "yoy"), BigDecimal.class).as("yoy"));
        main.addFrom(DSL.table(windowed).as("w"));
        main.addConditions(outputPeriod.ge(DSL.field("date_trunc({0}, {1})", Timestamp.class,
                DSL.inline(unit), DSL.val(from))));
        main.addOrderBy(outputPeriod);
        for (SemanticManifest.Dimension dimension : dimensions) {
            main.addOrderBy(DSL.field(DSL.name("w", dimension.getId())));
        }
        int limit = queryDsl.getLimit() == null ? 500 : queryDsl.getLimit();
        main.addLimit(limit);

        Query query = main;
        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId(manifest.getManifestId());
        compiled.setContentHash(manifest.getContentHash());
        compiled.setMode(QueryDsl.AnalysisMode.YOY_MOM.name());
        compiled.setMetricId(metric.getId());
        compiled.setDimensions(dimensionIds);
        compiled.setJoins(List.of());
        compiled.setDialect("postgresql");
        compiled.setSql(query.getSQL(ParamType.INDEXED));
        compiled.setBindValues(query.getBindValues());
        compiled.setAppliedLimit(limit);
        compiled.setCompiledAt(Instant.now());
        return compiled;
    }

    /**
     * Instant → 数据库侧「上海本地时间」的 Timestamp。
     * 绑定值随后被 cast 成 timestamp（无时区），与 timestamptz 列比较时由执行器固定会话时区
     * 为 Asia/Shanghai，二者才一致；直接用 Timestamp.from 会依赖 JVM 默认时区。
     */
    private static Timestamp shanghaiTimestamp(Instant instant) {
        return Timestamp.valueOf(instant.atZone(
                java.time.ZoneId.of(TimeExpressionParser.ZONE)).toLocalDateTime());
    }

    private static org.jooq.GroupField[] gridDimensionFields(
            List<SemanticManifest.Dimension> dimensions) {
        org.jooq.GroupField[] fields = new org.jooq.GroupField[dimensions.size()];
        for (int i = 0; i < dimensions.size(); i++) {
            fields[i] = DSL.field(DSL.name("g", dimensions.get(i).getId()));
        }
        return fields;
    }

    /** "table.column" / "schema.table.column" → 表限定名；裸列名返回 null。 */
    private static String tableOf(String column) {
        if (column == null) {
            return null;
        }
        int lastDot = column.lastIndexOf('.');
        return lastDot <= 0 ? null : column.substring(0, lastDot);
    }

    private static Field<?> aggregate(SemanticManifest.Metric metric) {
        if (metric.getAggregation() == null) {
            throw exception(DSL_INVALID, "指标缺少 aggregation: " + metric.getId());
        }
        Name column = assetColumn(metric.getTable(), metric.getColumn());
        Field<BigDecimal> decimalField = DSL.field(column, BigDecimal.class);
        return switch (metric.getAggregation()) {
            case SUM -> DSL.sum(decimalField);
            case COUNT -> DSL.count();
            case COUNT_DISTINCT -> DSL.countDistinct(DSL.field(column));
            case AVG -> DSL.avg(decimalField);
            case MIN -> DSL.min(decimalField);
            case MAX -> DSL.max(decimalField);
        };
    }

    private static Condition condition(QueryDsl.Filter filter, SemanticManifest.Dimension dimension) {
        Field<Object> field = DSL.field(assetColumn(dimension.getTable(), dimension.getColumn()));
        List<Object> values = new ArrayList<>(filter.getValues());
        return switch (filter.getOperator()) {
            case EQ -> field.eq(values.get(0));
            case IN -> field.in(values);
            case NOT_IN -> field.notIn(values);
            case GTE -> field.ge(values.get(0));
            case LTE -> field.le(values.get(0));
            case BETWEEN -> field.between(values.get(0), values.get(1));
        };
    }

    private static Condition timeCondition(QueryDsl.TimeRange range, SemanticManifest semantic) {
        // field 是资产 ID，与 filters 一致；物理列名只能从已发布语义清单解析，DSL 不得携带
        SemanticManifest.Dimension dimension = semantic.requireDimension(range.getField());
        Field<Timestamp> field = DSL.field(assetColumn(dimension.getTable(), dimension.getColumn()), Timestamp.class);
        // 与 funnel / yoy_mom 统一按「上海本地墙钟」绑定：会话时区已固定 Asia/Shanghai，
        // 用 Timestamp.from（JVM 默认时区墙钟）会让同一时间窗随部署时区漂移 8 小时。
        Timestamp from = shanghaiTimestamp(range.getFrom());
        Timestamp to = shanghaiTimestamp(range.getTo());
        return field.ge(from).and(field.le(to));
    }

    /**
     * 资产物理列：清单可能给限定名（schema.table.column）或裸列名（column）。
     * 裸列名用清单中的 table 补全为限定名，避免跨表 Join 时列歧义（ADR-0007）。
     */
    private static Name assetColumn(String table, String column) {
        if (column != null && !column.contains(".") && table != null && !table.isBlank()) {
            return columnName(table + "." + column);
        }
        return columnName(column);
    }

    private static Name columnName(String column) {
        return DSL.name(column.split("\\."));
    }

    private static Name tableName(String table) {
        return DSL.name(table.split("\\."));
    }

}
