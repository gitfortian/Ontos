package com.ontos.module.query.core.executor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.query.core.QueryProperties;
import com.ontos.module.query.core.compiler.CompiledQuery;
import com.ontos.module.query.core.time.TimeExpressionParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.query.enums.ErrorCodeConstants.PLAN_UNAVAILABLE;
import static com.ontos.module.query.enums.ErrorCodeConstants.QUERY_EXECUTION_FAILED;
import static com.ontos.module.query.enums.ErrorCodeConstants.QUERY_NOT_READ_ONLY;
import static com.ontos.module.query.enums.ErrorCodeConstants.QUERY_TIMEOUT;

/**
 * 查询执行器：只接受编译器产物，绝不接收任意用户 SQL。
 *
 * <p>每次执行在同一个连接内先设置事务级 RLS GUC（tenant/user），
 * 再由数据库原生策略做最终行级隔离；连接始终只读。
 */
@Slf4j
@Component
public class QueryExecutor {

    /**
     * 事务级会话上下文：RLS 身份 + 时区。
     * 时区必须固定为 Asia/Shanghai（ADR-0004 2.4）：date_trunc 与 timestamp/timestamptz
     * 比较都依赖会话时区，不固定会让「自然月」口径随部署环境漂移。
     */
    static final String SET_RLS_CONTEXT =
            "SELECT set_config('ontos.tenant_id', ?, true), set_config('ontos.user_id', ?, true),"
                    + " set_config('TimeZone', ?, true)";

    private final DataSource queryDataSource;
    private final QueryProperties properties;

    public QueryExecutor(@Qualifier("queryDataSource") DataSource queryDataSource, QueryProperties properties) {
        this.queryDataSource = queryDataSource;
        this.properties = properties;
    }

    public QueryResult execute(CompiledQuery compiled, RequestContext context) {
        return run(compiled, context, properties.getMaxRows(), properties.getStatementTimeout());
    }

    /**
     * dry-run：受限执行（M2 规格 §3.2/§3.3）——与正式执行共用同一编译产物、同一会话上下文，
     * 只是超时与行数上限更紧；展示转换与审计由调用方负责（调试通道，不推进业务状态机）。
     */
    public QueryResult executeDryRun(CompiledQuery compiled, RequestContext context) {
        return run(compiled, context, properties.getDryRunMaxRows(), properties.getDryRunTimeout());
    }

    private QueryResult run(CompiledQuery compiled, RequestContext context, int maxRows, Duration timeout) {
        assertReadOnly(compiled.getSql());
        long start = System.nanoTime();

        try (Connection connection = queryDataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                setRlsContext(connection, context);
                try (PreparedStatement statement = connection.prepareStatement(compiled.getSql())) {
                    bind(statement, compiled.getBindValues());
                    statement.setQueryTimeout(timeoutSeconds(timeout));
                    statement.setMaxRows(maxRows + 1);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        QueryResult result = read(resultSet, maxRows);
                        result.setManifestId(compiled.getManifestId());
                        result.setContentHash(compiled.getContentHash());
                        result.setElapsedMs((System.nanoTime() - start) / 1_000_000);
                        // 只在完整读完后提交；任何失败路径都回滚，不提交半程事务
                        connection.commit();
                        return result;
                    }
                }
            } catch (SQLException | RuntimeException ex) {
                rollbackQuietly(connection);
                if (ex instanceof SQLException sqlException) {
                    throw translate(sqlException);
                }
                throw ex;
            }
        } catch (SQLException ex) {
            log.error("[run][获取查询连接失败]", ex);
            throw exception(QUERY_EXECUTION_FAILED);
        }
    }

    /**
     * dry-plan / dry-run 共用：EXPLAIN (FORMAT JSON) 取真实计划与成本（只读、不执行业务查询）。
     * 与正式执行共用同一会话上下文（RLS GUC + Asia/Shanghai 时区），保证计划与执行口径一致。
     */
    public QueryPlan explain(CompiledQuery compiled, RequestContext context) {
        assertReadOnly(compiled.getSql());
        try (Connection connection = queryDataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                setRlsContext(connection, context);
                try (PreparedStatement statement = connection.prepareStatement(
                        "EXPLAIN (FORMAT JSON) " + compiled.getSql())) {
                    bind(statement, compiled.getBindValues());
                    statement.setQueryTimeout(timeoutSeconds(properties.getStatementTimeout()));
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            throw exception(PLAN_UNAVAILABLE, "EXPLAIN 无结果行");
                        }
                        QueryPlan plan = parsePlan(resultSet.getString(1));
                        if (plan.getEstimatedCost() > properties.getMaxPlanCost()) {
                            plan.setStatus(QueryPlan.BLOCKED);
                            plan.setBlockReason("EXPLAIN 估算成本 " + plan.getEstimatedCost()
                                    + " 超阈值 " + properties.getMaxPlanCost());
                        }
                        connection.commit();
                        return plan;
                    }
                }
            } catch (SQLException | RuntimeException ex) {
                rollbackQuietly(connection);
                if (ex instanceof SQLException sqlException) {
                    throw translate(sqlException);
                }
                throw ex;
            }
        } catch (SQLException ex) {
            log.error("[explain][获取查询连接失败]", ex);
            throw exception(QUERY_EXECUTION_FAILED);
        }
    }

    private static QueryPlan parsePlan(String json) {
        QueryPlan plan = new QueryPlan();
        plan.setPlanJson(json);
        try {
            JsonNode root = json == null ? null : JsonUtils.parseTree(json);
            JsonNode first = root == null ? null : (root.isArray() ? root.get(0) : root);
            JsonNode node = first == null ? null : first.path("Plan");
            if (node == null || node.isMissingNode()) {
                throw exception(PLAN_UNAVAILABLE, "计划 JSON 缺少 Plan 节点");
            }
            JsonNode cost = node.get("Total Cost");
            JsonNode rows = node.get("Plan Rows");
            plan.setEstimatedCost(cost == null ? 0 : cost.asDouble());
            plan.setEstimatedRows(rows == null ? 0 : rows.asLong());
        } catch (IllegalArgumentException ex) {
            throw exception(PLAN_UNAVAILABLE, "计划 JSON 解析失败");
        }
        return plan;
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException suppressed) {
            log.warn("[rollbackQuietly][回滚失败，交由连接池回收处理]", suppressed);
        }
    }

    /** 只读断言：只接受 select/with 开头，且全文（剥离引号标识符后）不得出现任何数据修改/DDL 关键字。 */
    private static void assertReadOnly(String sql) {
        String normalized = sql == null ? "" : sql.strip().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("select") && !normalized.startsWith("with")) {
            throw exception(QUERY_NOT_READ_ONLY);
        }
        // WITH 可携带数据修改 CTE（PostgreSQL 特性），单靠前缀不够；剥离 "..." 标识符后扫描关键字，
        // 防止列名/表名误伤。绑定值不进 SQL 文本，无需处理字面量。
        String withoutQuoted = normalized.replaceAll("\"[^\"]*\"", "\"?\"");
        if (FORBIDDEN_KEYWORDS.matcher(withoutQuoted).find()) {
            throw exception(QUERY_NOT_READ_ONLY);
        }
    }

    private static final java.util.regex.Pattern FORBIDDEN_KEYWORDS = java.util.regex.Pattern.compile(
            "\\b(insert|update|delete|merge|truncate|grant|revoke|alter|drop|create|copy|call|do|set|reset|lock|vacuum|analyze|reindex|cluster|comment|listen|notify)\\b");

    private static void setRlsContext(Connection connection, RequestContext context) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SET_RLS_CONTEXT)) {
            statement.setString(1, context.tenantId());
            statement.setString(2, context.userId());
            statement.setString(3, TimeExpressionParser.ZONE);
            statement.execute();
        }
    }

    private static void bind(PreparedStatement statement, List<Object> bindValues) throws SQLException {
        for (int i = 0; i < bindValues.size(); i++) {
            statement.setObject(i + 1, bindValues.get(i));
        }
    }

    /**
     * JDBC 的查询超时以「整秒」为单位，不足 1 秒会被截断为 0，而 0 在驱动里表示「无超时」——
     * 相当于把资源闸门关掉。这里统一向上取整并兜底 1 秒，避免配置成毫秒级时静默失效。
     */
    private static int timeoutSeconds(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return 1;
        }
        return (int) Math.max(1, (timeout.toMillis() + 999) / 1000);
    }

    private static QueryResult read(ResultSet resultSet, int maxRows) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        List<String> columns = new ArrayList<>(meta.getColumnCount());
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(meta.getColumnLabel(i));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (resultSet.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columns.size(); i++) {
                row.put(columns.get(i - 1), normalize(resultSet.getObject(i)));
            }
            rows.add(row);
        }

        QueryResult result = new QueryResult();
        result.setColumns(columns);
        result.setRows(rows);
        result.setRowCount(rows.size());
        result.setTruncated(truncated);
        return result;
    }

    private static Object normalize(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        return value;
    }

    private static RuntimeException translate(SQLException ex) {
        if (ex instanceof SQLTimeoutException) {
            return exception(QUERY_TIMEOUT);
        }
        log.error("[translate][查询执行失败]", ex);
        return exception(QUERY_EXECUTION_FAILED);
    }

}
