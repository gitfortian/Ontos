package com.ontos.module.datasource.plugin;

import com.ontos.spi.datasource.DataSourceCapability;
import com.ontos.spi.datasource.DataSourceCatalog;
import com.ontos.spi.datasource.DataSourceConnection;
import com.ontos.spi.datasource.DataSourceDescriptor;
import com.ontos.spi.datasource.DataSourcePluginException;
import com.ontos.spi.datasource.DataSourcePlugin;
import com.ontos.spi.datasource.Secret;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 测试桩插件：注册表发现与目录服务的固定返回，不建真实连接。 */
public final class StubDataSourcePlugin implements DataSourcePlugin {

    private final String type;
    private final String descriptorType;
    private final String apiVersion;
    private final Set<DataSourceCapability> capabilities;
    final List<String> schemas;
    final List<com.ontos.spi.datasource.TableMeta> tables;
    final List<com.ontos.spi.datasource.ColumnMeta> columns;

    /** 测试注入：非空时 catalog 抛出该异常，用于验证错误码映射。 */
    private DataSourcePluginException catalogFailure;

    public void failCatalogWith(DataSourcePluginException failure) {
        this.catalogFailure = failure;
    }

    /** ServiceLoader 发现要求公共无参构造。 */
    public StubDataSourcePlugin() {
        this("fake", "fake", DataSourceDescriptor.CURRENT_API_VERSION,
                Set.of(DataSourceCapability.CONNECTION_TEST,
                        DataSourceCapability.CATALOG_METADATA));
    }

    public StubDataSourcePlugin(String type, String descriptorType, String apiVersion,
                                Set<DataSourceCapability> capabilities) {
        this(type, descriptorType, apiVersion, capabilities,
                List.of("public", "sales"),
                List.of(new com.ontos.spi.datasource.TableMeta("orders", "BASE TABLE"),
                        new com.ontos.spi.datasource.TableMeta("channel", "BASE TABLE")),
                List.of(new com.ontos.spi.datasource.ColumnMeta("id", "integer", false),
                        new com.ontos.spi.datasource.ColumnMeta("amount", "numeric", true)));
    }

    public StubDataSourcePlugin(String type, String descriptorType, String apiVersion,
                                Set<DataSourceCapability> capabilities,
                                List<String> schemas,
                                List<com.ontos.spi.datasource.TableMeta> tables,
                                List<com.ontos.spi.datasource.ColumnMeta> columns) {
        this.type = type;
        this.descriptorType = descriptorType;
        this.apiVersion = apiVersion;
        this.capabilities = capabilities;
        this.schemas = schemas;
        this.tables = tables;
        this.columns = columns;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public DataSourceDescriptor descriptor() {
        return new DataSourceDescriptor(descriptorType, descriptorType, apiVersion,
                capabilities, List.of());
    }

    @Override
    public DataSourceConnection parse(Map<String, String> config) {
        Map<String, String> safe = config == null ? Map.of() : config;
        String url = safe.getOrDefault("jdbcUrl", "");
        if (url.isBlank()) {
            throw new DataSourcePluginException(
                    DataSourcePluginException.Operation.PARAMETER, "jdbcUrl 不能为空");
        }
        return new DataSourceConnection(url, safe.getOrDefault("username", "u"),
                new Secret(safe.getOrDefault("password", "")),
                safe.get("database"), safe.get("schema"), Map.of());
    }

    @Override
    public void testConnection(DataSourceConnection connection, Duration timeout) {
        if (!connection.jdbcUrl().startsWith("jdbc:")) {
            throw new DataSourcePluginException(
                    DataSourcePluginException.Operation.CONNECTIVITY, "非法 URL");
        }
    }

    @Override
    public DataSourceCatalog catalog(DataSourceConnection connection) {
        if (catalogFailure != null) {
            throw catalogFailure;
        }
        return new DataSourceCatalog() {
            @Override
            public List<String> schemas() {
                return schemas;
            }

            @Override
            public List<com.ontos.spi.datasource.TableMeta> tables(String schema) {
                if (schema == null || schema.isBlank()) {
                    throw new DataSourcePluginException(
                            DataSourcePluginException.Operation.PARAMETER, "schema 不能为空");
                }
                return tables;
            }

            @Override
            public List<com.ontos.spi.datasource.ColumnMeta> columns(
                    com.ontos.spi.datasource.TableRef table) {
                return columns;
            }

            @Override
            public void close() {
                // 测试桩无资源可释放。
            }
        };
    }
}
