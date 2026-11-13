package com.ontos.spi.datasource;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPI 契约测试：secret 永不出现在 toString，descriptor 规范化且 fail-fast。 */
class DataSourceContractTest {

    @Test
    void secretMasksValueInToString() {
        assertEquals("***", new Secret("plain-password").toString());
    }

    @Test
    void connectionToStringHidesPassword() {
        DataSourceConnection connection = new DataSourceConnection(
                "jdbc:postgresql://localhost:5432/db", "ontos",
                new Secret("plain-password"), "db", "public", Map.of());
        assertFalse(connection.toString().contains("plain-password"));
        assertTrue(connection.toString().contains("***"));
    }

    @Test
    void descriptorNormalizesDefaults() {
        DataSourceDescriptor descriptor = new DataSourceDescriptor(
                "postgresql", "  ", null, null, null);
        assertEquals("postgresql", descriptor.displayName());
        assertEquals(DataSourceDescriptor.CURRENT_API_VERSION, descriptor.apiVersion());
        assertTrue(descriptor.capabilities().isEmpty());
        assertTrue(descriptor.configFields().isEmpty());
    }

    @Test
    void descriptorRejectsBlankType() {
        assertThrows(IllegalArgumentException.class,
                () -> new DataSourceDescriptor(" ", "x", null, null, null));
    }

    @Test
    void connectionRejectsBlankUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new DataSourceConnection(" ", "u", new Secret(""), null, null, null));
    }
}
