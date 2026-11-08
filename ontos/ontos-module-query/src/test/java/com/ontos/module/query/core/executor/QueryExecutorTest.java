package com.ontos.module.query.core.executor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.compiler.CompiledQuery;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** W3/T3.3 深度审核回归：会话时区必须固定为 Asia/Shanghai（ADR-0004 2.4）。 */
class QueryExecutorTest {

    private static final RequestContext CONTEXT = new RequestContext(
            "tenant-1", "user-1", "session-1", 1, null, "trace-1", Set.of(), true);

    @Test
    void shouldPinSessionTimeZoneBeforeQuery() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(resultSet.next()).thenReturn(false);

        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId("manifest-001");
        compiled.setContentHash("hash-1");
        compiled.setSql("select 1");
        compiled.setBindValues(List.of());

        new QueryExecutor(dataSource, TestFixtures.queryProperties()).execute(compiled, CONTEXT);

        // 第一条 prepareStatement 是会话上下文：租户、用户、时区
        verify(statement).setString(1, "tenant-1");
        verify(statement).setString(2, "user-1");
        verify(statement).setString(3, "Asia/Shanghai");
    }

    @Test
    void shouldKeepTimeZoneInSessionContextStatement() {
        assertTrue(QueryExecutor.SET_RLS_CONTEXT.contains("set_config('TimeZone', ?, true)"));
        assertTrue(QueryExecutor.SET_RLS_CONTEXT.indexOf("TimeZone")
                > QueryExecutor.SET_RLS_CONTEXT.indexOf("ontos.tenant_id"));
        assertEquals(3, QueryExecutor.SET_RLS_CONTEXT.chars()
                .filter(ch -> ch == '?').count(), "上下文参数顺序必须固定为 租户/用户/时区");
    }

}
