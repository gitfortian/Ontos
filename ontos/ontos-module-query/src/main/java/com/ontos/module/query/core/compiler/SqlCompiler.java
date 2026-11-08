package com.ontos.module.query.core.compiler;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.query.core.time.TimeExpressionParser;
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
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_EVENT_MAPPING_MISSING;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_EVENT_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_TIME_WINDOW_MISSING;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_ATTRIBUTE_NOT_PROJECTABLE;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_CROSS_TABLE_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_IDENTITY_REQUIRED;

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

    private final DSLContext dsl = DSL.using(SQLDialect.POSTGRES);

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
        // 纵深防御（PoC-04 缺陷修复）：即使调用方绕过校验器，编译器也绝不把
        // 未实现的模式静默编译成聚合 SQL——只拒绝，不实现（funnel / yoy_mom 归 T3.3）。
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
        for (QueryDsl.ResolvedJoin join : joins) {
            // 纵深防御：JoinPathResolver 保证每条边都有待引入的表；
            // 缺失时整单拒绝，绝不把未展开的关系退化成 WHERE 等值条件（那会静默改变结果）。
            if (join.getTable() == null) {
                throw exception(DSL_INVALID, "解析后的 Join 缺少待引入表: " + join.getRelationId());
            }
            Condition on = DSL.field(columnName(join.getLeftColumn()))
                    .eq(DSL.field(columnName(join.getRightColumn())));
            select.addJoin(DSL.table(tableName(join.getTable())), on);
        }
        if (queryDsl.getFilters() != null) {
            for (QueryDsl.Filter filter : queryDsl.getFilters()) {
                SemanticManifest.Dimension dimension = semantic.requireDimension(filter.getFieldId());
                conditions.add(condition(filter, dimension));
            }
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
        List<String> steps = params.getSteps();

        List<OntologySnapshot.EventDef> events = new ArrayList<>();
        for (String step : steps) {
            OntologySnapshot.EventDef event = ontology.getEvents().stream()
                    .filter(candidate -> step.equals(candidate.getEventType()))
                    .findFirst()
                    .orElseThrow(() -> exception(FUNNEL_EVENT_NOT_FOUND, step));
            events.add(event);
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

        // cohort：首步事件 + 首次发生时间 + 首次触点维度值
        SelectQuery<Record> cohort = dsl.selectQuery();
        cohort.addSelect(participant.as("subject"));
        cohort.addSelect(DSL.min(eventTime).as("first_time"));
        for (SemanticManifest.Dimension dimension : dimensions) {
            cohort.addSelect(DSL.field(assetColumn(dimension.getTable(), dimension.getColumn()))
                    .as(DSL.name(dimension.getId())));
        }
        cohort.addFrom(DSL.table(tableName(eventTable)));
        if (!dimensions.isEmpty()) {
            cohort.addJoin(DSL.table(tableName(subjectTable)),
                    DSL.field(columnName(subject.getColumn())).eq(participant));
        }
        cohort.addConditions(
                eventType.eq(events.get(0).getEventTypeValue()),
                eventTime.ge(DSL.val(cohortFrom)),
                eventTime.le(DSL.val(cohortTo)));
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
            step.addConditions(
                    eventType.eq(event.getEventTypeValue()),
                    eventTime.gt(previousTime),
                    eventTime.le(DSL.field("cast({0} as timestamp) + cast({1} as interval)",
                            Timestamp.class, previousTime, DSL.inline(stepInterval))));
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
        boolean monthly = "MONTH".equals(params.getPeriod());
        String unit = monthly ? "month" : "year";
        int yoyLag = monthly ? 12 : 1;

        QueryDsl.TimeRange range = queryDsl.getTimeRange();
        SemanticManifest.Dimension timeDimension = semantic.requireDimension(range.getField());
        List<String> dimensionIds = queryDsl.getDimensions() == null
                ? List.of() : queryDsl.getDimensions();
        List<SemanticManifest.Dimension> dimensions = new ArrayList<>();
        for (String dimensionId : dimensionIds) {
            dimensions.add(semantic.requireDimension(dimensionId));
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
        agg.addConditions(timeField.ge(DSL.val(windowFrom)), timeField.le(DSL.val(to)));
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
            dims.addConditions(timeField.ge(DSL.val(windowFrom)), timeField.le(DSL.val(to)));
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
        Timestamp from = Timestamp.from(range.getFrom());
        Timestamp to = Timestamp.from(range.getTo());
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
