package com.ontos.module.datasource.service.view;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.datasource.dal.dataobject.DatasourceConfigRecord;
import com.ontos.module.datasource.plugin.DataSourcePluginRegistry;
import com.ontos.module.datasource.plugin.StubDataSourcePlugin;
import com.ontos.module.datasource.service.DataSourceCatalogService;
import com.ontos.module.datasource.service.config.DatasourceConfigService;
import com.ontos.module.datasource.support.InMemoryDataViewRepository;
import com.ontos.module.datasource.support.InMemoryDatasourceConfigRepository;
import com.ontos.spi.datasource.DataSourceCapability;
import com.ontos.spi.datasource.DataSourceDescriptor;
import com.ontos.spi.datasource.ColumnMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 数据视图资产：扫描固化幂等、漂移 diff、退役 fail-closed、ID 规范化。 */
class DataViewServiceTest {

    private static final String KEY = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=";

    private StubDataSourcePlugin stub;
    private InMemoryDataViewRepository repository;
    private com.ontos.module.datasource.service.config.DatasourceConfigService configService;
    private DataViewService service;

    @BeforeEach
    void setUp() {
        stub = new StubDataSourcePlugin("postgresql", "postgresql",
                DataSourceDescriptor.CURRENT_API_VERSION,
                Set.of(DataSourceCapability.CONNECTION_TEST, DataSourceCapability.CATALOG_METADATA),
                List.of("public"),
                List.of(new com.ontos.spi.datasource.TableMeta("orders", "BASE TABLE")),
                List.of(new ColumnMeta("id", "integer", false),
                        new ColumnMeta("amount", "numeric", true)),
                List.of(new DataSourceDescriptor.ConfigField("jdbcUrl", "JDBC URL", false, false, null),
                        new DataSourceDescriptor.ConfigField("host", "主机", true, false, null),
                        new DataSourceDescriptor.ConfigField("password", "密码", false, true, null)));
        repository = new InMemoryDataViewRepository();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("ontos.datasource.query.jdbc-url",
                        "jdbc:postgresql://localhost:5432/ontos_queryds")
                .withProperty("ontos.datasource.query.username", "ontos_query_ro")
                .withProperty("ontos.datasource.query.password", "ontos_query_ro")
                .withProperty("ontos.datasource.query.schema", "public")
                .withProperty("ontos.datasource.master-key", KEY);
        DataSourcePluginRegistry registry = new DataSourcePluginRegistry(Map.of("postgresql", stub));
        InMemoryDatasourceConfigRepository configRepository = new InMemoryDatasourceConfigRepository();
        var manifestService = new com.ontos.module.ontology.service.manifest.ManifestService() {
            @Override
            public com.ontos.module.ontology.core.PublishedManifest getActiveManifestOrNull() {
                return manifestWithProperties(List.of(
                        Map.of("id", "property.order.amount", "column", "orders.amount"),
                        Map.of("id", "property.order.order_no", "column", "orders.order_no")));
            }

            @Override
            public com.ontos.module.ontology.core.PublishedManifest validateManifest(String id) {
                throw new UnsupportedOperationException("test");
            }

            @Override
            public Optional<com.ontos.module.ontology.core.PublishedManifest> getManifest(String id) {
                return Optional.empty();
            }

            @Override
            public void loadActiveManifest() {
            }

            @Override
            public com.ontos.module.ontology.core.PublishedManifest activate(String id) {
                throw new UnsupportedOperationException("test");
            }
        };
        configService = new DatasourceConfigService(registry, configRepository, repository, environment);
        service = new DataViewService(repository,
                new DataSourceCatalogService(registry, environment, manifestService),
                configService,
                manifestService);
    }

    /** 最小清单桩：只含 properties（引用检测的唯一消费点）。 */
    private static com.ontos.module.ontology.core.PublishedManifest manifestWithProperties(
            List<Map<String, Object>> properties) {
        String propertiesJson = com.ontos.framework.common.util.json.JsonUtils.toJsonString(properties);
        String payloadJson = "{\"ontology\":{\"properties\":" + propertiesJson + "}}";
        com.ontos.module.ontology.core.PublishedManifest manifest =
                new com.ontos.module.ontology.core.PublishedManifest();
        manifest.setManifestId("test-manifest");
        manifest.setPayloadJson(payloadJson);
        return manifest;
    }

    @Test
    void scanCreatesViewWithNormalizedId() {
        DataViewService.ScanResult result = service.scan(null, "public", "orders", "订单表", null, "alice");

        assertEquals("view.orders", result.view().id());
        assertEquals("订单表", result.view().name());
        assertTrue(result.diff().contentChanged());
        assertEquals(2, result.diff().addedColumns().size());
        assertTrue(result.view().contentHash() != null && result.view().contentHash().length() == 64);
    }

    @Test
    void scanSameSourceTableUpsertsInsteadOfDuplicating() {
        service.scan(null, "public", "orders", "订单表", null, "alice");
        DataViewService.ScanResult second = service.scan(null, "public", "orders", "订单表2", null, "alice");

        assertEquals("view.orders", second.view().id());
        assertEquals("订单表2", second.view().name());
        assertEquals(1, repository.findAll().size());
        assertFalse(second.diff().contentChanged()); // 列未漂移，hash 不变
    }

    @Test
    void idNormalizationHandlesDirtyPhysicalNames() {
        DataViewService.ScanResult result =
                service.scan(null, "public", "Dw.Dwd-Order Info!", null, null, "alice");
        assertEquals("view.dw_dwd_order_info", result.view().id());
    }

    @Test
    void idConflictAppendsHashSuffix() {
        // "orders!" 与 "orders" 规范化后同为 orders：第二个必须追加 hash 尾
        service.scan(null, "public", "orders", null, null, "alice");
        stub.setColumns(List.of(new ColumnMeta("id", "integer", false)));
        DataViewService.ScanResult conflicting =
                service.scan(null, "public", "ORDERS!", null, null, "alice");
        assertNotEquals("view.orders", conflicting.view().id());
        assertTrue(conflicting.view().id().startsWith("view.orders-"));
    }

    @Test
    void rescanDetectsDriftAndFlagsReferencedRemovedColumns() {
        service.scan(null, "public", "orders", "订单表", null, "alice");
        // 漂移：amount 被删（生效清单 property.order.amount 引用中）、region 新增
        stub.setColumns(List.of(new ColumnMeta("id", "integer", false),
                new ColumnMeta("region", "text", true)));

        DataViewService.ScanResult result = service.rescan("view.orders", "alice");

        assertEquals(List.of("amount"), result.diff().removedColumns());
        assertEquals(List.of("region"), result.diff().addedColumns());
        // 生效清单 property.order.amount 引用了被删列 → referencedRemoved 告警
        assertEquals(List.of("property.order.amount"), result.referencedRemoved());
        assertTrue(result.diff().contentChanged());
    }

    @Test
    void retireFailsClosedWhenActiveManifestStillReferences() {
        service.scan(null, "public", "orders", "订单表", null, "alice");
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.retire("view.orders"));
        assertEquals(1_006_000_112, exception.getCode());
        assertEquals("active", repository.find("view.orders").orElseThrow().getStatus());
    }

    @Test
    void retireAndReactivateRoundTripWhenUnreferenced() {
        // 清单只引用 orders：先扫一张无关表（event 不在桩目录里 → 用 catalog 变更模拟）
        stub.setColumns(List.of(new ColumnMeta("id", "integer", false)));
        service.scan(null, "public", "orders", "无关表", null, "alice"); // 此时清单引用列已不在快照
        // 注意：清单仍引用 orders.amount → 仍应拒；换一张清单没引用的表
        stub.setColumns(List.of(new ColumnMeta("id", "integer", false),
                new ColumnMeta("amount", "numeric", true)));
        // 直接用反射式构建：扫描一张清单未引用的表需要桩提供该表目录——绕道：手工造视图
        // （仓储直插，绕过 scan 的列拉取；退役检查只看引用）
        com.ontos.module.datasource.dal.dataobject.DataViewRecord orphan =
                new com.ontos.module.datasource.dal.dataobject.DataViewRecord();
        orphan.setId("view.event");
        orphan.setName("事件表");
        orphan.setSourceSchema("public");
        orphan.setSourceTable("event");
        orphan.setColumnsJson("[]");
        orphan.setContentHash("hash-event");
        orphan.setStatus("active");
        orphan.setCreatedBy("tester");
        repository.insert(orphan);

        service.retire("view.event");
        assertEquals("retired", repository.find("view.event").orElseThrow().getStatus());
        service.activate("view.event");
        assertEquals("active", repository.find("view.event").orElseThrow().getStatus());
    }

    @Test
    void retireMissingFailsClosed() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.retire("view.no-such"));
        assertEquals(1_006_000_111, exception.getCode());
    }

    @Test
    void rescanMissingFailsClosed() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.rescan("view.no-such", "alice"));
        assertEquals(1_006_000_111, exception.getCode());
    }

    private static void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }

    @Test
    void scanRequestedIdManualOverrideAndGuards() {
        // 合法手改 ID 生效
        DataViewService.ScanResult ok = service.scan(null, "public", "orders", "订单表",
                "view.custom_orders", "alice");
        assertEquals("view.custom_orders", ok.view().id());
        // 非法格式 → 116
        ServiceException invalid = assertThrows(ServiceException.class,
                () -> service.scan(null, "public", "items", null, "view.Bad_Id!", "alice"));
        assertEquals(1_006_000_116, invalid.getCode());
        // 重复 → 113
        ServiceException dup = assertThrows(ServiceException.class,
                () -> service.scan(null, "public", "items", null, "view.custom_orders", "alice"));
        assertEquals(1_006_000_113, dup.getCode());
        // 覆盖路径忽略手改 ID（ID 不变）
        DataViewService.ScanResult rescan = service.scan(null, "public", "orders", "订单表2",
                "view.other", "alice");
        assertEquals("view.custom_orders", rescan.view().id());
    }

    @Test
    void activateFailsClosedWhenSourceConfigMissing() {
        // 建配置 → 扫描视图 → 退役 → 删除配置 → 重激活 fail-closed（G3）
        configService.create("冒烟源", "postgresql",
                Map.of("host", "localhost", "port", "5432", "database", "ontos_queryds",
                        "jdbcUrl", "jdbc:postgresql://localhost:5432/ontos_queryds"), "alice");
        String configId = configService.list().get(0).id();
        // 用未被清单桩引用的表（orders 被 orders.amount/order_no 引用，retire 会被 112 拦）
        DataViewService.ScanResult created = service.scan(configId, "public", "items", "明细表",
                null, "alice");
        service.retire(created.view().id());
        configService.delete(configId);
        ServiceException missing = assertThrows(ServiceException.class,
                () -> service.activate(created.view().id()));
        assertEquals(1_006_000_107, missing.getCode());
    }
}