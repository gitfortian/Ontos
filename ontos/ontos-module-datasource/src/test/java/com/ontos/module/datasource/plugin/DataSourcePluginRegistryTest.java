package com.ontos.module.datasource.plugin;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.spi.datasource.DataSourceCapability;
import com.ontos.spi.datasource.DataSourceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 注册表契约：发现、查重、apiVersion 与能力校验全部 fail-fast。 */
class DataSourcePluginRegistryTest {

    private static final StubDataSourcePlugin FAKE =
            new StubDataSourcePlugin("fake", "fake", DataSourceDescriptor.CURRENT_API_VERSION,
                    Set.of(DataSourceCapability.CONNECTION_TEST,
                            DataSourceCapability.CATALOG_METADATA));

    @Test
    void discoverFindsPluginRegisteredViaServicesFile() {
        DataSourcePluginRegistry registry = new DataSourcePluginRegistry();
        registry.discover();

        assertNotNull(registry.get("fake"));
        assertEquals(1, registry.all().size());
    }

    @Test
    void duplicateTypeFailsFast() {
        DataSourcePluginRegistry registry = new DataSourcePluginRegistry(Map.of());
        assertThrows(IllegalStateException.class, () -> registry.collect(
                List.of(FAKE, new StubDataSourcePlugin("fake", "fake",
                        DataSourceDescriptor.CURRENT_API_VERSION,
                        Set.of(DataSourceCapability.CONNECTION_TEST)))));
    }

    @Test
    void descriptorTypeMismatchFailsFast() {
        DataSourcePluginRegistry registry = new DataSourcePluginRegistry(Map.of());
        assertThrows(IllegalStateException.class, () -> registry.collect(List.of(
                new StubDataSourcePlugin("fake", "other",
                        DataSourceDescriptor.CURRENT_API_VERSION,
                        Set.of(DataSourceCapability.CONNECTION_TEST)))));
    }

    @Test
    void unsupportedApiVersionFailsFast() {
        DataSourcePluginRegistry registry = new DataSourcePluginRegistry(Map.of());
        assertThrows(IllegalStateException.class, () -> registry.collect(List.of(
                new StubDataSourcePlugin("fake", "fake", "0",
                        Set.of(DataSourceCapability.CONNECTION_TEST)))));
    }

    @Test
    void emptyCapabilitiesFailsFast() {
        DataSourcePluginRegistry registry = new DataSourcePluginRegistry(Map.of());
        assertThrows(IllegalStateException.class, () -> registry.collect(List.of(
                new StubDataSourcePlugin("fake", "fake",
                        DataSourceDescriptor.CURRENT_API_VERSION, Set.of()))));
    }

    @Test
    void unknownTypeMapsToPluginNotFound() {
        DataSourcePluginRegistry registry =
                new DataSourcePluginRegistry(Map.of("fake", FAKE));
        ServiceException exception = assertThrows(ServiceException.class,
                () -> registry.get("mysql"));
        assertEquals(1_006_000_101, exception.getCode());
    }
}
