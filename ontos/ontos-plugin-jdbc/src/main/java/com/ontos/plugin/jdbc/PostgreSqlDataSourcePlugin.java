package com.ontos.plugin.jdbc;

import com.ontos.spi.datasource.DataSourceCapability;
import com.ontos.spi.datasource.DataSourceDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL 数据源插件（B2 Phase 1）。
 *
 * <p>连接超时经驱动属性 {@code connectTimeout}（秒）显式声明，保证「测试连接」有界；
 * catalog 走 {@code information_schema}，schema 与表名一律参数绑定。
 */
public final class PostgreSqlDataSourcePlugin extends JdbcDataSourcePlugin {

    public static final String TYPE = "postgresql";

    private static final String DEFAULT_PORT = "5432";
    private static final String CONNECT_TIMEOUT_SECONDS = "10";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    protected String displayName() {
        return "PostgreSQL";
    }

    @Override
    protected Set<DataSourceCapability> capabilities() {
        return DataSourceDescriptor.capabilities(
                DataSourceCapability.CONNECTION_TEST, DataSourceCapability.CATALOG_METADATA);
    }

    @Override
    protected List<DataSourceDescriptor.ConfigField> configFields() {
        return List.of(
                field("jdbcUrl", "JDBC URL", false, false,
                        "jdbc:postgresql://host:5432/database"),
                field("host", "主机", true, false, "localhost"),
                field("port", "端口", false, false, DEFAULT_PORT),
                field("database", "数据库", true, false, "postgres"),
                field("schema", "默认 Schema", false, false, "public"),
                field("username", "用户名", true, false, null),
                field("password", "密码", false, true, null));
    }

    @Override
    protected String buildJdbcUrl(Map<String, String> config) {
        String host = normalize(config.get(KEY_HOST));
        if (host == null) {
            throw new IllegalArgumentException("host 不能为空（或直接提供 jdbcUrl）");
        }
        String database = normalize(config.get(KEY_DATABASE));
        if (database == null) {
            throw new IllegalArgumentException("database 不能为空（或直接提供 jdbcUrl）");
        }
        String port = normalize(config.get(KEY_PORT));
        return "jdbc:postgresql://" + host + ":" + (port == null ? DEFAULT_PORT : port)
                + "/" + database;
    }

    @Override
    protected Map<String, String> connectionProperties(Map<String, String> config) {
        return Map.of(
                "connectTimeout", CONNECT_TIMEOUT_SECONDS,
                "ApplicationName", "ontos-datasource-catalog");
    }
}
