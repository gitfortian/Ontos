package com.ontos.module.query.core.executor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.query.core.QueryProperties;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.compiler.CompiledQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 执行器回归：会话时区（T3.3）、EXPLAIN 计划（T4.1）、dry-run 资源闸门（T4.2）。 */
class QueryExecutorTest {

    private static final RequestContext CONTEXT = new RequestContext(
            "tenant-1", "user-1", "session-1", 1, null, "trace-1", Set.of(), true);

    private static CompiledQuery target() {
        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId("manifest-001");
        compiled.setContentHash("hash-1");
        compiled.setSql("select 1");
        compiled.setBindValues(List.of());
        return compiled;
    }

    private static DataSource dataSource(Connection connection) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }

    private static PreparedStatement emptyResult(Connection connection) throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(resultSet.next()).thenReturn(false);
        return statement;
    }

    @Test
    void shouldPinSessionTimeZoneBeforeQuery() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = emptyResult(connection);

        new QueryExecutor(dataSource(connection), TestFixtures.queryProperties()).execute(target(), CONTEXT);

        // 第一条 prepareStatement 是会话上下文：租户、用户、时区
        verify(statement).setString(1, "tenant-1");
        verify(statement).setString(2, "user-1");
        verify(statement).setString(3, "Asia/Shanghai");
    }

    @Test
    void shouldExplainWithoutExecutingBusinessQuery() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("[{\"Plan\":{\"Total Cost\":12.5,\"Plan Rows\":3}}]");

        QueryPlan plan = new QueryExecutor(dataSource(connection), TestFixtures.queryProperties())
                .explain(target(), CONTEXT);

        assertEquals(12.5, plan.getEstimatedCost());
        assertEquals(3L, plan.getEstimatedRows());
        assertEquals(QueryPlan.OK, plan.getStatus());
        ArgumentCaptor<String> sqls = ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(sqls.capture());
        assertTrue(sqls.getAllValues().stream()
                        .anyMatch(sql -> sql.startsWith("EXPLAIN (FORMAT JSON) ")),
                "必须走 EXPLAIN 而非执行业务查询: " + sqls.getAllValues());
    }

    @Test
    void shouldBlockPlanOverCostThreshold() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("[{\"Plan\":{\"Total Cost\":99999,\"Plan Rows\":10}}]");
        QueryProperties properties = TestFixtures.queryProperties();
        properties.setMaxPlanCost(100);

        QueryPlan plan = new QueryExecutor(dataSource(connection), properties).explain(target(), CONTEXT);

        assertEquals(QueryPlan.BLOCKED, plan.getStatus());
        assertTrue(plan.getBlockReason().contains("99999"));
    }

    @Test
    void shouldApplyTighterLimitsForDryRun() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = emptyResult(connection);
        QueryProperties properties = TestFixtures.queryProperties();
        properties.setMaxRows(500);
        properties.setStatementTimeout(Duration.ofSeconds(15));
        properties.setDryRunMaxRows(20);
        properties.setDryRunTimeout(Duration.ofSeconds(3));

        new QueryExecutor(dataSource(connection), properties).executeDryRun(target(), CONTEXT);

        verify(statement).setMaxRows(21);
        verify(statement).setQueryTimeout(3);
    }

    @Test
    void shouldClampSubSecondTimeoutToOneSecondInsteadOfDisablingIt() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = emptyResult(connection);
        QueryProperties properties = TestFixtures.queryProperties();
        // JDBC 以整秒为单位，500ms 截断成 0 会被驱动当成「无超时」，闸门静默失效
        properties.setDryRunTimeout(Duration.ofMillis(500));

        new QueryExecutor(dataSource(connection), properties).executeDryRun(target(), CONTEXT);

        verify(statement).setQueryTimeout(1);
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
