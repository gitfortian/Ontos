package com.ontos.module.query.core.executor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.query.core.QueryProperties;
import com.ontos.module.query.core.compiler.CompiledQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
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

    private static final String SET_RLS_CONTEXT =
            "SELECT set_config('ontos.tenant_id', ?, true), set_config('ontos.user_id', ?, true)";

    private final DataSource queryDataSource;
    private final QueryProperties properties;

    public QueryExecutor(@Qualifier("queryDataSource") DataSource queryDataSource, QueryProperties properties) {
        this.queryDataSource = queryDataSource;
        this.properties = properties;
    }

    public QueryResult execute(CompiledQuery compiled, RequestContext context) {
        assertReadOnly(compiled.getSql());
        int maxRows = properties.getMaxRows();
        long start = System.nanoTime();

        try (Connection connection = queryDataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                setRlsContext(connection, context);
                try (PreparedStatement statement = connection.prepareStatement(compiled.getSql())) {
                    bind(statement, compiled.getBindValues());
                    statement.setQueryTimeout((int) properties.getStatementTimeout().toSeconds());
                    statement.setMaxRows(maxRows + 1);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        QueryResult result = read(resultSet, maxRows);
                        result.setManifestId(compiled.getManifestId());
                        result.setContentHash(compiled.getContentHash());
                        result.setElapsedMs((System.nanoTime() - start) / 1_000_000);
                        return result;
                    }
                } finally {
                    connection.commit();
                }
            } catch (SQLException ex) {
                connection.rollback();
                throw translate(ex);
            }
        } catch (SQLException ex) {
            log.error("[execute][获取查询连接失败]", ex);
            throw exception(QUERY_EXECUTION_FAILED);
        }
    }

    private static void assertReadOnly(String sql) {
        String normalized = sql == null ? "" : sql.strip().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("select") && !normalized.startsWith("with")) {
            throw exception(QUERY_NOT_READ_ONLY);
        }
    }

    private static void setRlsContext(Connection connection, RequestContext context) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SET_RLS_CONTEXT)) {
            statement.setString(1, context.tenantId());
            statement.setString(2, context.userId());
            statement.execute();
        }
    }

    private static void bind(PreparedStatement statement, List<Object> bindValues) throws SQLException {
        for (int i = 0; i < bindValues.size(); i++) {
            statement.setObject(i + 1, bindValues.get(i));
        }
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
