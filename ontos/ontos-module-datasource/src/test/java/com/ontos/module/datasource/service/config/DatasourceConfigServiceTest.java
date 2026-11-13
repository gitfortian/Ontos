package com.ontos.module.datasource.service.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.datasource.core.DatasourceSecretCipher;
import com.ontos.module.datasource.dal.dataobject.DatasourceConfigRecord;
import com.ontos.module.datasource.plugin.DataSourcePluginRegistry;
import com.ontos.module.datasource.plugin.StubDataSourcePlugin;
import com.ontos.module.datasource.support.InMemoryDatasourceConfigRepository;
import com.ontos.spi.datasource.DataSourceCapability;
import com.ontos.spi.datasource.DataSourceDescriptor;
import com.ontos.spi.datasource.DataSourcePluginException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 注册配置服务：强制测试、secret 剥离加密、重名拒、脱敏视图、缺 key fail-closed。 */
class DatasourceConfigServiceTest {

    private static final String KEY = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=";

    /** 带字段声明的桩：host 必填、password 为 secret；jdbcUrl 缺省供测试连接。 */
    private StubDataSourcePlugin plugin;

    private InMemoryDatasourceConfigRepository repository;
    private com.ontos.module.datasource.support.InMemoryDataViewRepository viewRepository;
    private DatasourceConfigService service;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        plugin = new StubDataSourcePlugin("postgresql", "postgresql",
                DataSourceDescriptor.CURRENT_API_VERSION,
                Set.of(DataSourceCapability.CONNECTION_TEST, DataSourceCapability.CATALOG_METADATA),
                List.of("public"),
                List.of(new com.ontos.spi.datasource.TableMeta("orders", "BASE TABLE")),
                List.of(new com.ontos.spi.datasource.ColumnMeta("id", "integer", false)),
                List.of(
                        new DataSourceDescriptor.ConfigField("jdbcUrl", "JDBC URL", false, false,
                                "jdbc:postgresql://host:5432/db"),
                        new DataSourceDescriptor.ConfigField("host", "主机", true, false, null),
                        new DataSourceDescriptor.ConfigField("database", "数据库", true, false, null),
                        new DataSourceDescriptor.ConfigField("password", "密码", false, true, null)));
        repository = new InMemoryDatasourceConfigRepository();
        viewRepository = new com.ontos.module.datasource.support.InMemoryDataViewRepository();
        environment = new MockEnvironment()
                .withProperty("ontos.datasource.master-key", KEY);
        service = new DatasourceConfigService(
                new DataSourcePluginRegistry(Map.of("postgresql", plugin)),
                repository, viewRepository, environment);
    }

    private Map<String, Object> config(String password) {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("host", "localhost");
        config.put("port", "5432");
        config.put("database", "ontos_queryds");
        config.put("jdbcUrl", "jdbc:postgresql://localhost:5432/ontos_queryds");
        if (password != null) {
            config.put("password", password);
        }
        return config;
    }

    @Test
    void createEncryptsSecretAndStripsItFromStoredConfig() {
        DatasourceConfigService.ConfigView view =
                service.create("本地查询库", "postgresql", config("s3cr3t"), "alice");

        assertFalse(view.config().containsValue("s3cr3t"));
        assertEquals(DatasourceConfigService.SECRET_MASK, view.config().get("password"));
        assertTrue(view.hasSecret());

        // 落库层：config 无明文密码；secret 列为密文（不是明文）
        DatasourceConfigRecord stored = repository.find(view.id()).orElseThrow();
        assertFalse(stored.getConfigJson().contains("s3cr3t"));
        assertNotEquals("s3cr3t", stored.getSecret());
        assertTrue(stored.getSecret().contains("==") || stored.getSecret().length() > 40);
        assertEquals("alice", stored.getCreatedBy());
    }

    @Test
    void createRejectsWhenConnectionTestFails() {
        // Stub 桩：非 jdbc: 前缀的 URL 在 testConnection 抛 CONNECTIVITY
        Map<String, Object> bad = config(null);
        bad.put("jdbcUrl", "not-a-url");
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.create("坏源", "postgresql", bad, "alice"));
        assertEquals(1_006_000_103, exception.getCode());
        assertTrue(repository.findAll().isEmpty());
    }

    @Test
    void createRejectsMissingRequiredField() {
        Map<String, Object> missing = new java.util.LinkedHashMap<>(config(null));
        missing.remove("database");
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.create("缺字段", "postgresql", missing, "alice"));
        assertEquals(1_006_000_102, exception.getCode());
    }

    @Test
    void createRejectsDuplicateName() {
        service.create("同名", "postgresql", config(null), "alice");
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.create("同名", "postgresql", config(null), "bob"));
        assertEquals(1_006_000_108, exception.getCode());
    }

    @Test
    void updateWithoutSecretsKeepsStoredCiphertext() {
        DatasourceConfigService.ConfigView created =
                service.create("本地库", "postgresql", config("old-pass"), "alice");
        String originalCiphertext = repository.find(created.id()).orElseThrow().getSecret();

        DatasourceConfigService.ConfigView updated =
                service.update(created.id(), "本地库-改名", config(null), null);

        assertEquals("本地库-改名", updated.name());
        // 密文未变（保留原凭据）
        assertEquals(originalCiphertext, repository.find(created.id()).orElseThrow().getSecret());
        // 解密回读 = 原凭据
        assertEquals("old-pass", new DatasourceSecretCipher(KEY)
                .decrypt(repository.find(created.id()).orElseThrow().getSecret()).get("password"));
    }

    @Test
    void updateWithSecretsReEncrypts() {
        DatasourceConfigService.ConfigView created =
                service.create("本地库", "postgresql", config("old-pass"), "alice");
        String originalCiphertext = repository.find(created.id()).orElseThrow().getSecret();

        service.update(created.id(), "本地库", config(null), Map.of("password", "new-pass"));

        String newCiphertext = repository.find(created.id()).orElseThrow().getSecret();
        assertNotEquals(originalCiphertext, newCiphertext);
        assertEquals("new-pass", new DatasourceSecretCipher(KEY)
                .decrypt(newCiphertext).get("password"));
    }

    @Test
    void updateRejectsDuplicateNameOfOtherRecord() {
        DatasourceConfigService.ConfigView first = service.create("甲", "postgresql", config(null), "alice");
        service.create("乙", "postgresql", config(null), "alice");
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.update(first.id(), "乙", config(null), null));
        assertEquals(1_006_000_108, exception.getCode());
    }

    @Test
    void deleteMissingFailsClosed() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.delete("no-such-id"));
        assertEquals(1_006_000_107, exception.getCode());
    }

    @Test
    void deleteFailsClosedWhenActiveViewReferences() {
        service.create("被引用源", "postgresql", config(null), "alice");
        String id = repository.findAll().get(0).getId();
        // 挂一个 active 视图引用该配置
        com.ontos.module.datasource.dal.dataobject.DataViewRecord view =
                new com.ontos.module.datasource.dal.dataobject.DataViewRecord();
        view.setId("view.blocker");
        view.setName("拦截视图");
        view.setSourceConfigId(id);
        view.setSourceSchema("public");
        view.setSourceTable("orders");
        view.setColumnsJson("[]");
        view.setContentHash("h");
        view.setStatus("active");
        viewRepository.insert(view);
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.delete(id));
        assertEquals(1_006_000_115, exception.getCode());
        // 退役后允许删除（retired 视图只是历史元数据，运行时编译走清单快照）
        view.setStatus("retired");
        viewRepository.updateStatus(view.getId(), "active", "retired");
        service.delete(id);
        assertTrue(repository.findAll().isEmpty());
    }

    @Test
    void createWithSecretFailsClosedWhenMasterKeyMissing() {
        DatasourceConfigService keyless = new DatasourceConfigService(
                new DataSourcePluginRegistry(Map.of("postgresql", plugin)),
                repository, viewRepository, new MockEnvironment());
        ServiceException exception = assertThrows(ServiceException.class,
                () -> keyless.create("无key源", "postgresql", config("s3cr3t"), "alice"));
        assertEquals(1_006_000_109, exception.getCode());
        // 不带病落库
        assertTrue(repository.findAll().isEmpty());
    }

    @Test
    void browseUsesStoredConfigAndMasksView() {
        service.create("浏览源", "postgresql", config(null), "alice");
        String id = repository.findAll().get(0).getId();
        var view = service.browse(id, null);
        assertEquals("postgresql", view.type());
        assertEquals("public", view.schema());
        assertTrue(view.tables().stream().anyMatch(table -> "orders".equals(table.name())));
    }

    @Test
    void testStoredConfigSucceeds() {
        service.create("测试源", "postgresql", config(null), "alice");
        String id = repository.findAll().get(0).getId();
        service.test(id); // 不抛即通过
    }

    @Test
    void wrongKeyOnDecryptOfStoredRecordFailsClosed() {
        DatasourceConfigService.ConfigView created =
                service.create("换key库", "postgresql", config("s3cr3t"), "alice");
        DatasourceConfigService wrongKey = new DatasourceConfigService(
                new DataSourcePluginRegistry(Map.of("postgresql", plugin)),
                repository, viewRepository, new MockEnvironment()
                        .withProperty("ontos.datasource.master-key",
                                "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1+fic="));
        // testStoredConfig 要解密：错 key → 110
        ServiceException exception = assertThrows(ServiceException.class,
                () -> wrongKey.test(created.id()));
        assertEquals(1_006_000_110, exception.getCode());
    }

}
