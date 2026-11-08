package com.ontos.module.query.core.compiler;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.query.core.dsl.QueryDsl;
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

    private final DSLContext dsl = DSL.using(SQLDialect.POSTGRES);

    public CompiledQuery compile(QueryDsl queryDsl, PublishedManifest manifest) {
        // 纵深防御（PoC-04 缺陷修复）：即使调用方绕过校验器，编译器也绝不把
        // 非 QUERY 模式静默编译成聚合 SQL——只拒绝，不实现（能力归 W3 后续任务）。
        QueryDsl.AnalysisMode mode = queryDsl.getMode() == null ? QueryDsl.AnalysisMode.QUERY : queryDsl.getMode();
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
