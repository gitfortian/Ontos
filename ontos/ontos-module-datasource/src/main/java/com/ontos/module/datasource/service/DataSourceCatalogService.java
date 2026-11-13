package com.ontos.module.datasource.service;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.datasource.enums.ErrorCodeConstants;
import com.ontos.module.datasource.plugin.DataSourcePluginRegistry;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.spi.datasource.DataSourceCapability;
import com.ontos.spi.datasource.DataSourceCatalog;
import com.ontos.spi.datasource.DataSourceConnection;
import com.ontos.spi.datasource.DataSourcePlugin;
import com.ontos.spi.datasource.DataSourcePluginException;
import com.ontos.spi.datasource.TableRef;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_CATALOG_FAILED;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_CONFIG_INVALID;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_CONNECT_FAILED;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_NOT_CONFIGURED;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_TYPE_INVALID;

/**
 * 数据源目录服务（B2 Phase 1）：把「当前查询库配置」投影成插件连接，提供真实库目录浏览、
 * 连通性测试与摘要视图。
 *
 * <p>边界：本服务只读——不建池、不改配置、不写库；SQL 仍由 Ontos 编译链产出并经
 * {@code QueryExecutor} 只读执行。当前查询库固定按 {@code postgresql} 插件解析，因为
 * 双数据源（system/query）即 PG；多源由 Phase 2 的 datasetId 化承接。
 */
@Service
public class DataSourceCatalogService {

    static final String CURRENT_TYPE = "postgresql";

    private static final String QUERY_URL_KEY = "ontos.datasource.query.jdbc-url";
    private static final String QUERY_USER_KEY = "ontos.datasource.query.username";
    private static final String QUERY_PASSWORD_KEY = "ontos.datasource.query.password";
    private static final String QUERY_SCHEMA_KEY = "ontos.datasource.query.schema";
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);
    /** 只脱敏 URL 中的 user:password@ 段（贪婪到最后一个 @），保留 host/db 便于排查。 */
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(//)([^/]+)@");

    private final DataSourcePluginRegistry registry;
    private final Environment environment;
    private final ManifestService manifestService;

    public DataSourceCatalogService(DataSourcePluginRegistry registry,
                                    Environment environment,
                                    ManifestService manifestService) {
        this.registry = registry;
        this.environment = environment;
        this.manifestService = manifestService;
    }

    /** 连接信息 + 清单引用表 + 已注册插件；URL 脱敏后输出。 */
    public DataSourceSummaryView summary() {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        List<DataSourceSummaryView.PluginView> plugins = registry.all().stream()
                .map(plugin -> new DataSourceSummaryView.PluginView(
                        plugin.type(), plugin.descriptor().displayName(),
                        plugin.descriptor().capabilities().stream()
                                .map(Enum::name)
                                .collect(Collectors.toUnmodifiableSet()),
                        plugin.descriptor().configFields().stream()
                                .map(field -> new DataSourceSummaryView.ConfigFieldView(
                                        field.key(), field.label(), field.required(),
                                        field.secret(), field.placeholder()))
                                .toList()))
                .toList();
        return new DataSourceSummaryView(
                "query",
                "查询库（只读）",
                maskCredentials(environment.getProperty(QUERY_URL_KEY, "")),
                true,
                environment.getProperty(QUERY_SCHEMA_KEY, "public"),
                manifest == null ? null : manifest.getManifestId(),
                manifestTablesOf(manifest),
                plugins);
    }

    /** 真实库目录：schema 列表 + 指定 schema 的表。schema 缺省取连接配置或 public。 */
    public DataSourceCatalogView browse(String schema) {
        DataSourceConnection connection = currentConnection();
        DataSourceCatalog catalog = null;
        try {
            DataSourcePlugin plugin = registry.get(CURRENT_TYPE);
            catalog = plugin.catalog(connection);
            List<String> schemas = catalog.schemas();
            String effective = schema != null && !schema.isBlank() ? schema.trim()
                    : connection.schema() != null ? connection.schema() : "public";
            return new DataSourceCatalogView(CURRENT_TYPE, schemas, effective, catalog.tables(effective));
        } catch (DataSourcePluginException exception) {
            throw map(exception);
        } finally {
            if (catalog != null) {
                catalog.close();
            }
        }
    }

    /** 指定表的列；schema/table 缺参在服务层前置拒绝，不进插件。 */
    public List<DataSourceColumnView> columns(String schema, String table) {
        if (schema == null || schema.isBlank() || table == null || table.isBlank()) {
            throw exception(DATASOURCE_CONFIG_INVALID, "schema 与 table 不能为空");
        }
        DataSourceConnection connection = currentConnection();
        DataSourceCatalog catalog = null;
        try {
            DataSourcePlugin plugin = registry.get(CURRENT_TYPE);
            catalog = plugin.catalog(connection);
            TableRef reference = new TableRef(schema.trim(), table.trim());
            return catalog.columns(reference).stream()
                    .map(DataSourceColumnView::of)
                    .toList();
        } catch (DataSourcePluginException exception) {
            throw map(exception);
        } catch (IllegalArgumentException exception) {
            throw exception(DATASOURCE_CONFIG_INVALID, exception.getMessage());
        } finally {
            if (catalog != null) {
                catalog.close();
            }
        }
    }

    /** 连通性测试：type 决定插件，config 由插件解析；任何失败按 Operation 映射业务错误码。 */
    public void test(String type, Map<String, String> config) {
        DataSourcePlugin plugin = registry.get(type);
        if (!plugin.supports(DataSourceCapability.CONNECTION_TEST)) {
            throw exception(DATASOURCE_TYPE_INVALID, "插件未声明连接测试能力: " + type);
        }
        try {
            DataSourceConnection connection = plugin.parse(config == null ? Map.of() : config);
            plugin.testConnection(connection, TEST_TIMEOUT);
        } catch (DataSourcePluginException exception) {
            throw map(exception);
        }
    }

    /**
     * 连通性测试（当前查询库配置）：复用 {@link #currentConnection()} 的连接，不接收请求体配置；
     * 页面「测试连接」按钮用，避免把凭据经前端绕一圈。
     */
    public void testCurrent() {
        DataSourcePlugin plugin = registry.get(CURRENT_TYPE);
        if (!plugin.supports(DataSourceCapability.CONNECTION_TEST)) {
            throw exception(DATASOURCE_TYPE_INVALID, "插件未声明连接测试能力: " + CURRENT_TYPE);
        }
        try {
            plugin.testConnection(currentConnection(), TEST_TIMEOUT);
        } catch (DataSourcePluginException exception) {
            throw map(exception);
        }
    }

    /** 由既有查询库配置键构造插件连接；未配置时 fail-closed（页面给出明确提示）。 */
    private DataSourceConnection currentConnection() {
        String url = environment.getProperty(QUERY_URL_KEY, "");
        if (url.isBlank()) {
            throw exception(DATASOURCE_NOT_CONFIGURED);
        }
        Map<String, String> config = Map.of(
                "jdbcUrl", url,
                "username", environment.getProperty(QUERY_USER_KEY, ""),
                "password", environment.getProperty(QUERY_PASSWORD_KEY, ""),
                "schema", environment.getProperty(QUERY_SCHEMA_KEY, "public"));
        try {
            return registry.get(CURRENT_TYPE).parse(config);
        } catch (DataSourcePluginException exception) {
            throw map(exception);
        }
    }

    private ServiceException map(DataSourcePluginException exception) {
        return switch (exception.operation()) {
            case PARAMETER -> exception(DATASOURCE_CONFIG_INVALID, exception.getMessage());
            case CONNECTIVITY -> exception(DATASOURCE_CONNECT_FAILED, exception.getMessage());
            case CATALOG -> exception(DATASOURCE_CATALOG_FAILED, exception.getMessage());
        };
    }

    /** 按 {@code Property.column} 最后一段之前反推表名，按表名排序输出。 */
    private static List<DataSourceSummaryView.ManifestTable> manifestTablesOf(
            PublishedManifest manifest) {
        if (manifest == null || manifest.getPayloadJson() == null
                || manifest.getPayloadJson().isBlank()) {
            return List.of();
        }
        ManifestPayload payload = JsonUtils.parseObject(manifest.getPayloadJson(), ManifestPayload.class);
        OntologySnapshot ontology = payload.getOntology();
        if (ontology == null || ontology.getProperties() == null) {
            return List.of();
        }
        Map<String, List<String>> byTable = new TreeMap<>();
        for (OntologySnapshot.Property property : ontology.getProperties()) {
            String table = tableOf(property.getColumn());
            if (table == null) {
                continue;
            }
            byTable.computeIfAbsent(table, key -> new ArrayList<>()).add(property.getId());
        }
        return byTable.entrySet().stream()
                .map(entry -> new DataSourceSummaryView.ManifestTable(
                        entry.getKey(), entry.getValue().size(), List.copyOf(entry.getValue())))
                .toList();
    }

    private static String tableOf(String column) {
        if (column == null || column.isBlank()) {
            return null;
        }
        int index = column.lastIndexOf('.');
        return index <= 0 ? null : column.substring(0, index);
    }

    static String maskCredentials(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return URL_CREDENTIALS.matcher(url).replaceAll("$1***@");
    }
}
