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
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;

/**
 * 确定性 SQL 编译器：只依据已发布的语义映射与已校验 DSL 生成 SQL。
 *
 * <p>当前骨架实现单表聚合（指标 + 维度 + 过滤 + 时间窗 + limit）；
 * 跨表 Join 由本体路径规划补齐，缺失时 DslValidator 已 fail-closed，不在此处降级。
 */
@Component
public class SqlCompiler {

    private static final String VALUE_ALIAS = "value";

    private final DSLContext dsl = DSL.using(SQLDialect.POSTGRES);

    public CompiledQuery compile(QueryDsl queryDsl, PublishedManifest manifest) {
        SemanticManifest semantic = manifest.getSemantic();
        SemanticManifest.Metric metric = semantic.requireMetric(queryDsl.getMetricId());
        List<String> dimensionIds = queryDsl.getDimensions() == null ? List.of() : queryDsl.getDimensions();

        SelectQuery<Record> select = dsl.selectQuery();
        select.addSelect(aggregate(metric).as(VALUE_ALIAS));

        List<Field<?>> groupFields = new ArrayList<>();
        for (String dimensionId : dimensionIds) {
            SemanticManifest.Dimension dimension = semantic.requireDimension(dimensionId);
            Field<Object> field = DSL.field(columnName(dimension.getColumn()));
            select.addSelect(field.as(DSL.name(dimensionId)));
            groupFields.add(field);
        }
        select.addGroupBy(groupFields);

        select.addFrom(DSL.table(tableName(metric.getTable())));

        List<Condition> conditions = new ArrayList<>();
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
        Name column = columnName(metric.getColumn());
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
        Field<Object> field = DSL.field(columnName(dimension.getColumn()));
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
        String column = semantic.requireDimension(range.getField()).getColumn();
        Field<Timestamp> field = DSL.field(columnName(column), Timestamp.class);
        Timestamp from = Timestamp.from(range.getFrom());
        Timestamp to = Timestamp.from(range.getTo());
        return field.ge(from).and(field.le(to));
    }

    private static Name columnName(String column) {
        return DSL.name(column.split("\\."));
    }

    private static Name tableName(String table) {
        return DSL.name(table.split("\\."));
    }

}
