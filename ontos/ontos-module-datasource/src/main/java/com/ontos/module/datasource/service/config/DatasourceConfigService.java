package com.ontos.module.datasource.service.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.datasource.core.DatasourceSecretCipher;
import com.ontos.module.datasource.dal.dataobject.DatasourceConfigRecord;
import com.ontos.module.datasource.dal.mysql.DatasourceConfigRepository;
import com.ontos.module.datasource.plugin.DataSourcePluginRegistry;
import com.ontos.module.datasource.service.DataSourceCatalogView;
import com.ontos.module.datasource.service.DataSourceColumnView;
import com.ontos.module.datasource.service.DataSourceCatalogService;
import com.ontos.spi.datasource.DataSourceCapability;
import com.ontos.spi.datasource.DataSourceCatalog;
import com.ontos.spi.datasource.DataSourceConnection;
import com.ontos.spi.datasource.DataSourceDescriptor;
import com.ontos.spi.datasource.DataSourcePlugin;
import com.ontos.spi.datasource.DataSourcePluginException;
import com.ontos.spi.datasource.TableRef;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_CATALOG_FAILED;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_CONFIG_INVALID;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_CONFIG_NOT_FOUND;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_CONFIG_REFERENCED;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_CONNECT_FAILED;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_MASTER_KEY_MISSING;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_NAME_DUPLICATE;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_PLUGIN_NOT_FOUND;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_SECRET_CIPHER_FAILED;

/**
 * 注册数据源配置服务（B2 Phase 2 前半 / ADR-0025）：注册数据源的 CRUD、按存储配置测试与目录浏览。
 *
 * <p>边界与铁律：
 * <ol>
 *   <li>**查询执行链零改动**——本服务只管理「注册」与「元数据浏览」；业务查询仍走 application.yml
 *   的当前查询库（datasetId 化执行属 Phase 2 后半）；</li>
 *   <li>**凭据安全**——secret 字段按插件 {@code secret=true} 声明从 config 剥离，经
 *   {@link DatasourceSecretCipher}（AES-256-GCM，master key 环境注入）加密落库；视图层永远
 *   返回掩码，明文凭据不进日志、不进响应；</li>
 *   <li>**fail-closed**——注册/编辑强制测试连接通过；master key 缺失时加密与解密一律拒绝，
 *   不降级明文；类型必须已注册（防拼错类型注册成幽灵源）。</li>
 * </ol>
 */
@Service
public class DatasourceConfigService {

    static final java.time.Duration TEST_TIMEOUT = java.time.Duration.ofSeconds(10);
    /** 视图层对已存储 secret 字段的掩码值。 */
    public static final String SECRET_MASK = "••••";

    private final DataSourcePluginRegistry registry;
    private final DatasourceConfigRepository repository;
    /** 数据视图仓储（G1 删除守卫：active 视图引用即拒删）。 */
    private final com.ontos.module.datasource.dal.mysql.DataViewRepository viewRepository;
    private final DatasourceSecretCipher cipher;

    public DatasourceConfigService(DataSourcePluginRegistry registry,
                                   DatasourceConfigRepository repository,
                                   com.ontos.module.datasource.dal.mysql.DataViewRepository viewRepository,
                                   Environment environment) {
        this.registry = registry;
        this.repository = repository;
        this.viewRepository = viewRepository;
        this.cipher = new DatasourceSecretCipher(environment.getProperty("ontos.datasource.master-key"));
    }

    /** 注册：required 校验 → 强制测试通过 → secret 剥离加密 → 落库。返回脱敏视图。 */
    public ConfigView create(String name, String type,
                             Map<String, Object> config, String actor) {
        if (type == null || type.isBlank()) {
            throw exception(DATASOURCE_CONFIG_INVALID, "注册必须提供 type");
        }
        if (name == null || name.isBlank()) {
            throw exception(DATASOURCE_CONFIG_INVALID, "名称不能为空");
        }
        DataSourcePlugin plugin = requirePlugin(type);
        Map<String, String> flat = flatten(config);
        requireRequiredFields(plugin, flat);
        testOrThrow(plugin, flat);
        if (repository.findByName(name).isPresent()) {
            throw exception(DATASOURCE_NAME_DUPLICATE, name);
        }
        DatasourceConfigRecord record = new DatasourceConfigRecord();
        record.setId(UUID.randomUUID().toString());
        record.setName(name);
        record.setType(type);
        record.setConfigJson(storeConfigJson(plugin, flat));
        record.setSecret(encryptSecrets(plugin, flat));
        record.setCreatedBy(actor);
        repository.insert(record);
        return viewOf(repository.find(record.getId()).orElse(record));
    }

    /** 编辑：type 不可改；secrets 缺省保留原密文；提供了则重新加密。返回脱敏视图。 */
    public ConfigView update(String id, String name,
                             Map<String, Object> config,
                             Map<String, String> secrets) {
        DatasourceConfigRecord current = require(id);
        DataSourcePlugin plugin = requirePlugin(current.getType());
        Map<String, String> flat = flatten(config);
        requireRequiredFields(plugin, flat);
        // 合并凭据：显式提供 → 新值；未提供 → 解密旧密文（也顺带校验 key 可用）
        Map<String, String> merged = new LinkedHashMap<>(flat);
        if (secrets == null || secrets.isEmpty()) {
            if (current.getSecret() != null) {
                merged.putAll(cipher.decrypt(current.getSecret()));
            }
        } else {
            merged.putAll(secrets);
        }
        requireRequiredFields(plugin, merged);
        testOrThrow(plugin, merged);
        String duplicate = repository.findByName(name)
                .filter(other -> !other.getId().equals(id))
                .map(DatasourceConfigRecord::getName)
                .orElse(null);
        if (duplicate != null) {
            throw exception(DATASOURCE_NAME_DUPLICATE, name);
        }
        String newSecret = (secrets == null || secrets.isEmpty())
                ? null
                : encryptSecrets(plugin, merged);
        repository.update(id, name, storeConfigJson(plugin, merged), newSecret);
        return viewOf(require(id));
    }

    /** 删除：仍被 active 数据视图引用时 fail-closed（G1）——引用视图的目录/重扫描依赖本配置。 */
    public void delete(String id) {
        require(id);
        java.util.List<com.ontos.module.datasource.dal.dataobject.DataViewRecord> referencing =
                viewRepository.findAllActiveByConfig(id);
        if (!referencing.isEmpty()) {
            throw exception(DATASOURCE_CONFIG_REFERENCED,
                    id + "（" + referencing.size() + " 个视图："
                            + referencing.stream().map(r -> r.getId()).limit(5).reduce(
                                    (a, b) -> a + ", " + b).orElse("") + "…）");
        }
        if (repository.delete(id) == 0) {
            throw exception(DATASOURCE_CONFIG_NOT_FOUND, id);
        }
    }

    /** 存在性检查（不解密凭据，activate 守卫用，G3）。 */
    public boolean configExists(String id) {
        return repository.find(id).isPresent();
    }

    /** 脱敏视图列表：secret 字段以掩码出现，明文/密文不出服务。 */
    public List<ConfigView> list() {
        return repository.findAll().stream().map(this::viewOf).toList();
    }

    public ConfigView view(String id) {
        return viewOf(require(id));
    }

    /** 按存储配置测试连接。 */
    public void test(String id) {
        DatasourceConfigRecord record = require(id);
        DataSourcePlugin plugin = requirePlugin(record.getType());
        testOrThrow(plugin, fullConfig(record));
    }

    /** 按存储配置浏览真实目录。 */
    public DataSourceCatalogView browse(String id, String schema) {
        DatasourceConfigRecord record = require(id);
        DataSourcePlugin plugin = requirePlugin(record.getType());
        DataSourceCatalog catalog = null;
        try {
            DataSourceConnection connection = plugin.parse(fullConfig(record));
            catalog = plugin.catalog(connection);
            List<String> schemas = catalog.schemas();
            String effective = schema != null && !schema.isBlank() ? schema.trim()
                    : connection.schema() != null ? connection.schema() : "public";
            return new DataSourceCatalogView(record.getType(), schemas, effective,
                    catalog.tables(effective));
        } catch (DataSourcePluginException exception) {
            throw map(exception);
        } finally {
            if (catalog != null) {
                catalog.close();
            }
        }
    }

    /** 按存储配置读表列。 */
    public List<DataSourceColumnView> columns(String id, String schema, String table) {
        if (schema == null || schema.isBlank() || table == null || table.isBlank()) {
            throw exception(DATASOURCE_CONFIG_INVALID, "schema 与 table 不能为空");
        }
        DatasourceConfigRecord record = require(id);
        DataSourcePlugin plugin = requirePlugin(record.getType());
        DataSourceCatalog catalog = null;
        try {
            DataSourceConnection connection = plugin.parse(fullConfig(record));
            catalog = plugin.catalog(connection);
            return catalog.columns(new TableRef(schema.trim(), table.trim())).stream()
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

    // ---------- 内部 ----------

    /** 类型必须已注册：registry.get 对未知类型直接抛 101（与 catalog 服务同一口径）。 */
    private DataSourcePlugin requirePlugin(String type) {
        return registry.get(type);
    }

    private DatasourceConfigRecord require(String id) {
        return repository.find(id)
                .orElseThrow(() -> exception(DATASOURCE_CONFIG_NOT_FOUND, id));
    }

    private void requireRequiredFields(DataSourcePlugin plugin, Map<String, String> flat) {
        for (DataSourceDescriptor.ConfigField field : plugin.descriptor().configFields()) {
            if (field.required() && (flat.get(field.key()) == null || flat.get(field.key()).isBlank())) {
                throw exception(DATASOURCE_CONFIG_INVALID, "必填字段缺失: " + field.key());
            }
        }
    }

    /** 测试失败按既有口径映射 CONNECT_FAILED；测试强制通过才允许注册/编辑。 */
    private void testOrThrow(DataSourcePlugin plugin, Map<String, String> flat) {
        if (!plugin.supports(DataSourceCapability.CONNECTION_TEST)) {
            throw exception(DATASOURCE_PLUGIN_NOT_FOUND, "插件未声明连接测试能力: " + plugin.type());
        }
        try {
            DataSourceConnection connection = plugin.parse(flat);
            plugin.testConnection(connection, TEST_TIMEOUT);
        } catch (DataSourcePluginException exception) {
            throw map(exception);
        }
    }

    /** config 只存非 secret 字段（值转字符串）。 */
    private String storeConfigJson(DataSourcePlugin plugin, Map<String, String> flat) {
        Map<String, String> plain = new LinkedHashMap<>();
        for (DataSourceDescriptor.ConfigField field : plugin.descriptor().configFields()) {
            if (!field.secret() && flat.get(field.key()) != null) {
                plain.put(field.key(), flat.get(field.key()));
            }
        }
        return JsonUtils.toJsonString(plain);
    }

    /** secret 字段剥离并加密；全空返回 null（无凭据源）。key 缺失 fail-closed。 */
    private String encryptSecrets(DataSourcePlugin plugin, Map<String, String> flat) {
        Map<String, String> secrets = new LinkedHashMap<>();
        for (String key : plugin.descriptor().secretKeys()) {
            String value = flat.get(key);
            if (value != null && !value.isBlank()) {
                secrets.put(key, value);
            }
        }
        if (secrets.isEmpty()) {
            return null;
        }
        // key 缺失 → cipher 抛 109；加密失败 → 110。直接透传，不吞不换。
        return cipher.encrypt(secrets);
    }

    /** 存储配置 + 解密凭据 = 插件完整入参。 */
    private Map<String, String> fullConfig(DatasourceConfigRecord record) {
        Map<String, String> merged = new LinkedHashMap<>();
        Map<String, Object> stored = JsonUtils.parseMap(record.getConfigJson());
        stored.forEach((key, value) -> merged.put(key, String.valueOf(value)));
        if (record.getSecret() != null) {
            // key 缺失/密文损坏 → cipher 抛 109/110，直接透传
            merged.putAll(cipher.decrypt(record.getSecret()));
        }
        return merged;
    }

    private ConfigView viewOf(DatasourceConfigRecord record) {
        DataSourcePlugin plugin = requirePlugin(record.getType());
        Map<String, Object> masked = new LinkedHashMap<>();
        JsonUtils.parseMap(record.getConfigJson())
                .forEach((key, value) -> masked.put(key, value));
        for (String key : plugin.descriptor().secretKeys()) {
            masked.put(key, record.getSecret() == null ? "" : SECRET_MASK);
        }
        return new ConfigView(record.getId(), record.getName(), record.getType(), masked,
                record.getSecret() != null, record.getCreatedAt(), record.getUpdatedAt());
    }

    private com.ontos.framework.common.exception.ServiceException map(DataSourcePluginException exception) {
        return switch (exception.operation()) {
            case PARAMETER -> exception(DATASOURCE_CONFIG_INVALID, exception.getMessage());
            case CONNECTIVITY -> exception(DATASOURCE_CONNECT_FAILED, exception.getMessage());
            case CATALOG -> exception(DATASOURCE_CATALOG_FAILED, exception.getMessage());
        };
    }

    private static Map<String, String> flatten(Map<String, Object> config) {
        Map<String, String> flat = new LinkedHashMap<>();
        if (config != null) {
            config.forEach((key, value) -> {
                if (value != null) {
                    flat.put(key, String.valueOf(value));
                }
            });
        }
        return flat;
    }

    /** 视图：掩码配置 + 元信息。 */
    public record ConfigView(String id, String name, String type, Map<String, Object> config,
                             boolean hasSecret, java.time.OffsetDateTime createdAt,
                             java.time.OffsetDateTime updatedAt) {
    }

}
