package com.ontos.plugin.jdbc;

import com.ontos.spi.datasource.DataSourceCapability;
import com.ontos.spi.datasource.DataSourceCatalog;
import com.ontos.spi.datasource.DataSourceConnection;
import com.ontos.spi.datasource.DataSourceDescriptor;
import com.ontos.spi.datasource.DataSourcePlugin;
import com.ontos.spi.datasource.DataSourcePluginException;
import com.ontos.spi.datasource.ColumnMeta;
import com.ontos.spi.datasource.Secret;
import com.ontos.spi.datasource.TableMeta;
import com.ontos.spi.datasource.TableRef;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * JDBC 系插件公共基座（B2）：解析、连通性测试与 information_schema 元数据读取。
 *
 * <p>子类只回答三件事：类型 / 展示名、能力与配置字段、JDBC URL 组装；连接参数里的驱动
 * 特有项通过 {@link #connectionProperties(Map)} 提供（如 PG 的 connectTimeout）。
 *
 * <p>硬边界：本基座只开放元数据读取；SQL 仍由 Ontos 编译链产出并经只读通道执行。
 * 驱动连接超时必须由子类通过驱动属性显式声明，基座不做全局 {@code setLoginTimeout}。
 */
public abstract class JdbcDataSourcePlugin implements DataSourcePlugin {

    protected static final String KEY_JDBC_URL = "jdbcUrl";
    protected static final String KEY_HOST = "host";
    protected static final String KEY_PORT = "port";
    protected static final String KEY_DATABASE = "database";
    protected static final String KEY_SCHEMA = "schema";
    protected static final String KEY_USERNAME = "username";
    protected static final String KEY_PASSWORD = "password";

    private final DataSourceDescriptor descriptor;

    protected JdbcDataSourcePlugin() {
        this.descriptor = new DataSourceDescriptor(type(), displayName(),
                DataSourceDescriptor.CURRENT_API_VERSION, capabilities(), configFields());
    }

    @Override
    public final DataSourceDescriptor descriptor() {
        return descriptor;
    }

    /** 展示名；缺省回退为 type。 */
    protected abstract String displayName();

    /** 本插件声明的能力（Phase 1 至少包含 CONNECTION_TEST）。 */
    protected abstract Set<DataSourceCapability> capabilities();

    /** 连接配置字段声明；secret 字段必须显式标注。 */
    protected abstract List<DataSourceDescriptor.ConfigField> configFields();

    /** 未提供 jdbcUrl 时按配置组装；缺参抛 {@link IllegalArgumentException}。 */
    protected abstract String buildJdbcUrl(Map<String, String> config);

    /** 驱动特有连接属性（如连接超时）；子类必须保证连接阶段有界。 */
    protected Map<String, String> connectionProperties(Map<String, String> config) {
        return Map.of();
    }

    @Override
    public final DataSourceConnection parse(Map<String, String> config) {
        Map<String, String> safe = config == null ? Map.of() : Map.copyOf(config);
        try {
            String jdbcUrl = normalize(safe.get(KEY_JDBC_URL));
            if (jdbcUrl == null) {
                jdbcUrl = buildJdbcUrl(safe);
            }
            String username = normalize(safe.get(KEY_USERNAME));
            if (username == null) {
                throw new IllegalArgumentException("username 不能为空");
            }
            return new DataSourceConnection(jdbcUrl, username,
                    new Secret(safe.getOrDefault(KEY_PASSWORD, "")),
                    normalize(safe.get(KEY_DATABASE)), normalize(safe.get(KEY_SCHEMA)),
                    connectionProperties(safe));
        } catch (IllegalArgumentException exception) {
            throw new DataSourcePluginException(
                    DataSourcePluginException.Operation.PARAMETER, exception.getMessage(), exception);
        }
    }

    @Override
    public final void testConnection(DataSourceConnection connection, Duration timeout) {
        int seconds = Math.max(1, (int) Math.min(Integer.MAX_VALUE, timeout.toSeconds()));
        try (Connection raw = open(connection)) {
            if (!raw.isValid(seconds)) {
                throw new DataSourcePluginException(
                        DataSourcePluginException.Operation.CONNECTIVITY,
                        "连接校验未通过：" + displayName());
            }
        } catch (SQLException exception) {
            throw new DataSourcePluginException(
                    DataSourcePluginException.Operation.CONNECTIVITY,
                    "连接失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public final DataSourceCatalog catalog(DataSourceConnection connection) {
        try {
            return new InformationSchemaCatalog(open(connection));
        } catch (SQLException exception) {
            throw new DataSourcePluginException(
                    DataSourcePluginException.Operation.CATALOG,
                    "建立元数据连接失败：" + exception.getMessage(), exception);
        }
    }

    private Connection open(DataSourceConnection connection) throws SQLException {
        Properties properties = new Properties();
        properties.putAll(connection.properties());
        if (connection.username() != null) {
            properties.setProperty("user", connection.username());
        }
        properties.setProperty("password", connection.password().value());
        return DriverManager.getConnection(connection.jdbcUrl(), properties);
    }

    protected static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    protected static DataSourceDescriptor.ConfigField field(
            String key, String label, boolean required, boolean secret, String placeholder) {
        return new DataSourceDescriptor.ConfigField(key, label, required, secret, placeholder);
    }

    /**
     * 基于 {@code information_schema} 的目录读取器；一个实例持有一条连接，
     * 必须以 try-with-resources 使用，schema 与表名一律走参数绑定。
     */
    private static final class InformationSchemaCatalog implements DataSourceCatalog {

        private final Connection connection;

        private InformationSchemaCatalog(Connection connection) {
            this.connection = connection;
        }

        @Override
        public List<String> schemas() {
            // 标准约定：information_schema 与 pg_* 系统 schema 对用户不可见；\ 转义 LIKE 中的下划线。
            String sql = "select schema_name from information_schema.schemata "
                    + "where schema_name <> 'information_schema' "
                    + "and schema_name not like 'pg\\_%' order by schema_name";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                List<String> schemas = new ArrayList<>();
                while (resultSet.next()) {
                    schemas.add(resultSet.getString(1));
                }
                return List.copyOf(schemas);
            } catch (SQLException exception) {
                throw new DataSourcePluginException(
                        DataSourcePluginException.Operation.CATALOG,
                        "读取 schema 失败：" + exception.getMessage(), exception);
            }
        }

        @Override
        public List<TableMeta> tables(String schema) {
            if (schema == null || schema.isBlank()) {
                throw new DataSourcePluginException(
                        DataSourcePluginException.Operation.PARAMETER, "schema 不能为空");
            }
            String sql = "select table_name, table_type from information_schema.tables "
                    + "where table_schema = ? order by table_name";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, schema);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<TableMeta> tables = new ArrayList<>();
                    while (resultSet.next()) {
                        tables.add(new TableMeta(resultSet.getString(1), resultSet.getString(2)));
                    }
                    return List.copyOf(tables);
                }
            } catch (SQLException exception) {
                throw new DataSourcePluginException(
                        DataSourcePluginException.Operation.CATALOG,
                        "读取表元数据失败：" + exception.getMessage(), exception);
            }
        }

        @Override
        public List<ColumnMeta> columns(TableRef table) {
            String sql = "select column_name, data_type, is_nullable "
                    + "from information_schema.columns "
                    + "where table_schema = ? and table_name = ? order by ordinal_position";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, table.schema());
                statement.setString(2, table.table());
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<ColumnMeta> columns = new ArrayList<>();
                    while (resultSet.next()) {
                        columns.add(new ColumnMeta(resultSet.getString(1), resultSet.getString(2),
                                "YES".equalsIgnoreCase(resultSet.getString(3))));
                    }
                    return List.copyOf(columns);
                }
            } catch (SQLException exception) {
                throw new DataSourcePluginException(
                        DataSourcePluginException.Operation.CATALOG,
                        "读取列元数据失败：" + exception.getMessage(), exception);
            }
        }

        @Override
        public void close() {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 关闭失败仅影响连接释放，不再向外抛。
            }
        }
    }
}
