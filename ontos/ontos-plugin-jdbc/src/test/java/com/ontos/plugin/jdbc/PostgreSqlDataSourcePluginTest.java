package com.ontos.plugin.jdbc;

import com.ontos.spi.datasource.DataSourceCapability;
import com.ontos.spi.datasource.DataSourceConnection;
import com.ontos.spi.datasource.DataSourcePluginException;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PG 插件契约：URL 组装、参数 fail-fast、secret 遮蔽与连通性失败映射。 */
class PostgreSqlDataSourcePluginTest {

    private final PostgreSqlDataSourcePlugin plugin = new PostgreSqlDataSourcePlugin();

    @Test
    void buildsUrlFromHostPortDatabase() {
        DataSourceConnection connection = plugin.parse(Map.of(
                "host", "localhost", "database", "ontos_queryds", "username", "u"));
        assertEquals("jdbc:postgresql://localhost:5432/ontos_queryds", connection.jdbcUrl());
        // schema 不在插件层给默认值；默认 schema 由平台配置或连接显式声明
        assertEquals(null, connection.schema());
    }

    @Test
    void explicitJdbcUrlOverridesParts() {
        DataSourceConnection connection = plugin.parse(Map.of(
                "jdbcUrl", "jdbc:postgresql://h:6543/x", "username", "u"));
        assertEquals("jdbc:postgresql://h:6543/x", connection.jdbcUrl());
    }

    @Test
    void missingHostFailsAsParameter() {
        DataSourcePluginException exception = assertThrows(DataSourcePluginException.class,
                () -> plugin.parse(Map.of("database", "x", "username", "u")));
        assertEquals(DataSourcePluginException.Operation.PARAMETER, exception.operation());
    }

    @Test
    void missingUsernameFailsAsParameter() {
        DataSourcePluginException exception = assertThrows(DataSourcePluginException.class,
                () -> plugin.parse(Map.of("host", "h", "database", "x")));
        assertEquals(DataSourcePluginException.Operation.PARAMETER, exception.operation());
    }

    @Test
    void connectionToStringHidesPassword() {
        DataSourceConnection connection = plugin.parse(Map.of(
                "host", "h", "database", "d", "username", "u", "password", "topsecret"));
        assertFalse(connection.toString().contains("topsecret"));
        assertEquals("***", connection.password().toString());
    }

    @Test
    void connectTimeoutIsDeclared() {
        DataSourceConnection connection = plugin.parse(Map.of(
                "host", "h", "database", "d", "username", "u"));
        assertEquals("10", connection.properties().get("connectTimeout"));
    }

    @Test
    void descriptorMatchesTypeAndCapabilities() {
        assertEquals("postgresql", plugin.type());
        assertEquals("postgresql", plugin.descriptor().type());
        assertTrue(plugin.supports(DataSourceCapability.CONNECTION_TEST));
        assertTrue(plugin.supports(DataSourceCapability.CATALOG_METADATA));
    }

    @Test
    void unreachableHostFailsAsConnectivity() {
        DataSourceConnection connection = plugin.parse(Map.of(
                "host", "127.0.0.1", "port", "1", "database", "d",
                "username", "u", "password", "p"));
        DataSourcePluginException exception = assertThrows(DataSourcePluginException.class,
                () -> plugin.testConnection(connection, Duration.ofSeconds(1)));
        assertEquals(DataSourcePluginException.Operation.CONNECTIVITY, exception.operation());
    }
}
