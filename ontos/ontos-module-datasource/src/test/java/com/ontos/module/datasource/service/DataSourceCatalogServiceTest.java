package com.ontos.module.datasource.service;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.datasource.plugin.DataSourcePluginRegistry;
import com.ontos.module.datasource.plugin.StubDataSourcePlugin;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.spi.datasource.DataSourceCapability;
import com.ontos.spi.datasource.DataSourcePluginException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 目录服务契约：配置投影、真实目录浏览、错误码映射与 URL 脱敏。 */
class DataSourceCatalogServiceTest {

    private static final String PAYLOAD = """
            {"ontology":{"properties":[
              {"id":"property.order.amount","column":"orders.amount"},
              {"id":"property.order.order_no","column":"orders.order_no"},
              {"id":"property.channel.name","column":"channel.name"}
            ]}}
            """;

    private final StubDataSourcePlugin stub =
            new StubDataSourcePlugin("postgresql", "postgresql",
                    com.ontos.spi.datasource.DataSourceDescriptor.CURRENT_API_VERSION,
                    Set.of(DataSourceCapability.CONNECTION_TEST,
                            DataSourceCapability.CATALOG_METADATA));
    private final DataSourcePluginRegistry registry =
            new DataSourcePluginRegistry(Map.of("postgresql", stub));

    private MockEnvironment environmentWithUrl() {
        return new MockEnvironment()
                .withProperty("ontos.datasource.query.jdbc-url",
                        "jdbc:postgresql://ontos:secret@localhost:5432/ontos_queryds")
                .withProperty("ontos.datasource.query.username", "ontos")
                .withProperty("ontos.datasource.query.password", "secret")
                .withProperty("ontos.datasource.query.schema", "public");
    }

    private DataSourceCatalogService service(MockEnvironment environment) {
        return new DataSourceCatalogService(registry, environment, manifestService());
    }

    private ManifestService manifestService() {
        ManifestService manifestService = mock(ManifestService.class);
        PublishedManifest manifest = new PublishedManifest();
        manifest.setManifestId("2026.11.12-twenty-second");
        manifest.setPayloadJson(PAYLOAD);
        when(manifestService.getActiveManifestOrNull()).thenReturn(manifest);
        return manifestService;
    }

    @Test
    void summaryMasksUrlAndListsPlugins() {
        DataSourceSummaryView view = service(environmentWithUrl()).summary();

        assertEquals("jdbc:postgresql://***@localhost:5432/ontos_queryds", view.jdbcUrl());
        assertTrue(view.readOnly());
        assertEquals("2026.11.12-twenty-second", view.manifestId());
        assertEquals(2, view.manifestTables().size());
        assertEquals(1, view.plugins().size());
        assertEquals("postgresql", view.plugins().getFirst().type());
        assertTrue(view.plugins().getFirst().capabilities()
                .contains("CATALOG_METADATA"));
    }

    @Test
    void browseUsesConfiguredSchemaByDefault() {
        DataSourceCatalogView view = service(environmentWithUrl()).browse(null);

        assertEquals("postgresql", view.type());
        assertEquals("public", view.schema());
        assertEquals(List.of("public", "sales"), view.schemas());
        assertEquals(2, view.tables().size());
    }

    @Test
    void browseHonorsExplicitSchema() {
        DataSourceCatalogView view = service(environmentWithUrl()).browse("sales");
        assertEquals("sales", view.schema());
    }

    @Test
    void browseWithoutConfigurationFailsClosed() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service(new MockEnvironment()).browse(null));
        assertEquals(1_006_000_106, exception.getCode());
    }

    @Test
    void columnsRequireSchemaAndTable() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service(environmentWithUrl()).columns(" ", "orders"));
        assertEquals(1_006_000_102, exception.getCode());
    }

    @Test
    void columnsMapFromPlugin() {
        List<DataSourceColumnView> columns =
                service(environmentWithUrl()).columns("public", "orders");
        assertEquals(2, columns.size());
        assertEquals("integer", columns.getFirst().dataType());
        assertTrue(columns.getLast().nullable());
    }

    @Test
    void testCurrentSucceedsWithConfiguredConnection() {
        service(environmentWithUrl()).testCurrent();
        // 不抛即通过：当前查询库配置经插件解析并测试成功。
    }

    @Test
    void testCurrentFailsClosedWithoutConfiguration() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service(new MockEnvironment()).testCurrent());
        assertEquals(1_006_000_106, exception.getCode());
    }

    @Test
    void testUnknownTypeMapsToPluginNotFound() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service(environmentWithUrl()).test("mysql", Map.of()));
        assertEquals(1_006_000_101, exception.getCode());
    }

    @Test
    void testParameterFailureMapsToConfigInvalid() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service(environmentWithUrl()).test("postgresql", Map.of()));
        assertEquals(1_006_000_102, exception.getCode());
    }

    @Test
    void testConnectivityFailureMapsToConnectFailed() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service(environmentWithUrl()).test("postgresql",
                        Map.of("jdbcUrl", "not-a-url")));
        assertEquals(1_006_000_103, exception.getCode());
    }

    @Test
    void testSucceedsForWellFormedUrl() {
        service(environmentWithUrl()).test("postgresql",
                Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/x", "username", "u"));
        // 不抛即通过：桩插件对合法 URL 直接放行。
    }

    @Test
    void catalogFailureMapsToCatalogFailed() {
        stub.failCatalogWith(new DataSourcePluginException(
                DataSourcePluginException.Operation.CATALOG, "库不可达"));
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service(environmentWithUrl()).browse("public"));
        assertEquals(1_006_000_104, exception.getCode());
    }

    @Test
    void urlCredentialsAreMasked() {
        assertEquals("jdbc:postgresql://***@localhost:5432/db",
                DataSourceCatalogService.maskCredentials(
                        "jdbc:postgresql://u:p@ss@localhost:5432/db"));
        assertEquals("jdbc:postgresql://localhost:5432/db",
                DataSourceCatalogService.maskCredentials(
                        "jdbc:postgresql://localhost:5432/db"));
    }
}
