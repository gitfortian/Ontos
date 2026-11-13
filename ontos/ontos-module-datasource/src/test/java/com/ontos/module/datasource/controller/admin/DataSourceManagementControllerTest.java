package com.ontos.module.datasource.controller.admin;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.ontos.module.datasource.plugin.DataSourcePluginRegistry;
import com.ontos.module.datasource.plugin.StubDataSourcePlugin;
import com.ontos.module.datasource.service.DataSourceCatalogService;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.ontology.service.publish.PublishGuard;
import com.ontos.spi.datasource.DataSourceCapability;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 控制器契约：管理面闸门前置，插件目录对合法调用方可见。 */
class DataSourceManagementControllerTest {

    private final StubDataSourcePlugin stub =
            new StubDataSourcePlugin("postgresql", "postgresql",
                    com.ontos.spi.datasource.DataSourceDescriptor.CURRENT_API_VERSION,
                    Set.of(DataSourceCapability.CONNECTION_TEST,
                            DataSourceCapability.CATALOG_METADATA));
    private final PublishGuard allow = new PublishGuard() {
        @Override
        public void requirePublishAllowed(String assetId) {
            // 测试桩：直接放行。
        }

        @Override
        public String currentPublisher() {
            return "tester";
        }
    };
    private final PublishGuard deny = new PublishGuard() {
        @Override
        public void requirePublishAllowed(String assetId) {
            throw new ServiceException(GlobalErrorCodeConstants.FORBIDDEN);
        }

        @Override
        public String currentPublisher() {
            return "tester";
        }
    };

    private DataSourceManagementController controller(PublishGuard guard) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("ontos.datasource.query.jdbc-url",
                        "jdbc:postgresql://localhost:5432/ontos_queryds")
                .withProperty("ontos.datasource.query.username", "ontos")
                .withProperty("ontos.datasource.query.password", "p")
                .withProperty("ontos.datasource.query.schema", "public");
        ManifestService manifestService = mock(ManifestService.class);
        when(manifestService.getActiveManifestOrNull()).thenReturn(null);
        DataSourceCatalogService service = new DataSourceCatalogService(
                new DataSourcePluginRegistry(Map.of("postgresql", stub)),
                environment, manifestService);
        return new DataSourceManagementController(service, guard);
    }

    @Test
    void summaryExposesPluginsAndConnectionForAllowedCaller() {
        var view = controller(allow).summary().getData();

        assertNotNull(view);
        assertEquals("query", view.id());
        assertEquals(1, view.plugins().size());
    }

    @Test
    void browseReturnsRealCatalogForAllowedCaller() {
        var view = controller(allow).browse(null).getData();

        assertNotNull(view);
        assertEquals("public", view.schema());
        assertEquals(2, view.tables().size());
    }

    @Test
    void columnsRejectBlankTable() {
        assertThrows(ServiceException.class,
                () -> controller(allow).columns("public", " "));
    }

    @Test
    void managementGateDeniesAllEndpoints() {
        DataSourceManagementController controller = controller(deny);
        ServiceException exception = assertThrows(ServiceException.class,
                controller::summary);
        assertEquals(403, exception.getCode());
        assertThrows(ServiceException.class, () -> controller.browse(null));
        assertThrows(ServiceException.class, () -> controller.columns("public", "orders"));
        assertThrows(ServiceException.class,
                () -> controller.test("postgresql", Map.of()));
    }

    @Test
    void testEndpointReturnsTrueOnSuccess() {
        assertEquals(true, controller(allow).test("postgresql",
                Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/x")).getData());
    }

    @Test
    void summaryReturnsNullManifestIdWhenNoActiveManifest() {
        // 无生效清单时摘要仍可用，manifestId 为 null（页面据此提示清单未加载）。
        var view = controller(allow).summary().getData();
        assertNull(view.manifestId());
    }
}
