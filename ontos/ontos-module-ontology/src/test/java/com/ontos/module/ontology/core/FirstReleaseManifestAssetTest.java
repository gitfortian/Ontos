package com.ontos.module.ontology.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.ontos.framework.common.util.json.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-0012：首发清单 v2 的事件判定映射必须能被结构校验接受。 */
class FirstReleaseManifestAssetTest {

    private static ManifestPayload load(String file) throws Exception {
        Path path = Path.of("..", "..", "ontos-assets", file);
        assertTrue(Files.exists(path), "资产文件缺失: " + path.toAbsolutePath());
        return JsonUtils.parseObject(Files.readString(path), ManifestPayload.class);
    }

    @Test
    void shouldAcceptV2ManifestWithEventMapping() throws Exception {
        ManifestPayload payload = load("first-release-v2.json");
        List<ManifestStructureValidator.Violation> violations =
                new ManifestStructureValidator().validate(payload);

        assertTrue(violations.isEmpty(), "v2 清单结构校验失败: " + violations);
        assertEquals("2026.11.8", payload.getOntology().getOntologyVersion());
        for (OntologySnapshot.EventDef event : payload.getOntology().getEvents()) {
            assertNotNull(event.getEventTypeProperty(), "缺少 eventTypeProperty: " + event.getId());
            assertNotNull(event.getEventTypeValue(), "缺少 eventTypeValue: " + event.getId());
            assertNotNull(event.getParticipantProperty(), "缺少 participantProperty: " + event.getId());
            assertEquals("property.event.occurred_at", event.getTimeProperty());
        }
    }

    @Test
    void shouldRejectEventMappingThatCrossesTables() throws Exception {
        ManifestPayload payload = load("first-release-v2.json");
        // 把事件类型属性指向 orders 表，制造跨表映射
        payload.getOntology().getEvents().get(0).setEventTypeProperty("property.order.order_no");

        List<ManifestStructureValidator.Violation> violations =
                new ManifestStructureValidator().validate(payload);

        assertFalse(violations.isEmpty(), "跨表事件映射必须被拒绝");
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("事件映射属性跨表")),
                "错误信息应指明跨表: " + violations);
    }

    @Test
    void shouldRoundTripV2WithoutDroppingEventMappingKeys() throws Exception {
        // 发布链路会做「提交树 vs 类型化回装树」的键差分；
        // ADR-0012 新增字段若被 Jackson 静默丢弃，v2 清单发布时会被未知字段策略拒绝。
        String json = Files.readString(Path.of("..", "..", "ontos-assets", "first-release-v2.json"));
        java.util.Map<?, ?> submitted = JsonUtils.parseObject(json, java.util.Map.class);
        ManifestPayload typed = JsonUtils.parseObject(json, ManifestPayload.class);
        java.util.Map<?, ?> roundTrip = JsonUtils.parseObject(
                JsonUtils.toJsonString(typed), java.util.Map.class);

        List<?> submittedEvents = (List<?>) ((java.util.Map<?, ?>) submitted.get("ontology")).get("events");
        List<?> roundTripEvents = (List<?>) ((java.util.Map<?, ?>) roundTrip.get("ontology")).get("events");
        assertEquals(submittedEvents.size(), roundTripEvents.size());
        for (int i = 0; i < submittedEvents.size(); i++) {
            assertEquals(((java.util.Map<?, ?>) submittedEvents.get(i)).keySet(),
                    ((java.util.Map<?, ?>) roundTripEvents.get(i)).keySet(),
                    "回装不得丢弃事件判定映射字段");
        }
    }

    @Test
    void shouldKeepV1ManifestPublishableWithoutMapping() throws Exception {
        // 向后兼容：旧清单不声明映射字段也应通过结构校验（funnel 编译期再 fail-closed）
        ManifestPayload payload = load("first-release.json");
        assertTrue(new ManifestStructureValidator().validate(payload).isEmpty());
        assertTrue(payload.getOntology().getEvents().stream()
                .allMatch(event -> event.getEventTypeProperty() == null));
    }

}
