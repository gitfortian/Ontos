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
            // 类型化回装会为新增可选字段补 null 键（如 channelPropertyId），所以断言方向是
            // 「提交树里的键一个都不能丢」（超集可接受，丢失即发布未知字段差分会拒绝）
            assertTrue(roundTripEvents.get(i) instanceof java.util.Map<?, ?> map
                    && map.keySet().containsAll(((java.util.Map<?, ?>) submittedEvents.get(i)).keySet()),
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

    @Test
    void shouldAcceptV11ManifestWithTouchChannelMapping() throws Exception {
        // M6/T1.2：v11 新增渠道触点事件（channelPropertyId）必须能被结构校验接受
        ManifestPayload payload = load("first-release-v11.json");
        List<ManifestStructureValidator.Violation> violations =
                new ManifestStructureValidator().validate(payload);

        assertTrue(violations.isEmpty(), "v11 清单结构校验失败: " + violations);
        assertEquals("2026.11.9", payload.getOntology().getOntologyVersion());
        OntologySnapshot.EventDef touch = payload.getOntology().getEvents().stream()
                .filter(event -> "event.channel_touch".equals(event.getId()))
                .findFirst().orElseThrow(() -> new AssertionError("v11 缺少渠道触点事件"));
        assertEquals("property.event.channel_id", touch.getChannelPropertyId());
        assertEquals("CHANNEL_TOUCH", touch.getEventType());
    }

    @Test
    void shouldAcceptV12ManifestWithChannelNameAndDedupKey() throws Exception {
        // M6/T1.4：归因需要渠道名称（维度取值来源）与事件去重键（last_touch 稳定排序）作为已发布属性
        ManifestPayload payload = load("first-release-v12.json");
        List<ManifestStructureValidator.Violation> violations =
                new ManifestStructureValidator().validate(payload);

        assertTrue(violations.isEmpty(), "v12 清单结构校验失败: " + violations);
        OntologySnapshot.Property channelName = payload.getOntology().getProperties().stream()
                .filter(property -> "property.channel.name".equals(property.getId()))
                .findFirst().orElseThrow(() -> new AssertionError("v12 缺少渠道名称属性"));
        assertEquals("channel.name", channelName.getColumn());
        assertEquals("concept.channel", channelName.getConceptId());
        OntologySnapshot.Property dedupKey = payload.getOntology().getProperties().stream()
                .filter(property -> "property.event.dedup_key".equals(property.getId()))
                .findFirst().orElseThrow(() -> new AssertionError("v12 缺少事件去重键属性"));
        assertEquals("event.dedup_key", dedupKey.getColumn());
        // 归因指标必须显式开放 ATTRIBUTION（gmv / order_count），否则入口闸门会拒
        assertTrue(payload.getSemantic().getMetrics().stream()
                .filter(metric -> "metric.gmv".equals(metric.getId()))
                .findFirst().orElseThrow().getAllowedModes().contains("ATTRIBUTION"));
    }

    @Test
    void shouldAcceptV13ManifestWithFullScenarioModel() throws Exception {
        // M6/T2.2：ScenarioMeta 升级为 v1.4 §5.7 完整模型，v13 必须通过结构校验
        ManifestPayload payload = load("first-release-v13.json");
        List<ManifestStructureValidator.Violation> violations =
                new ManifestStructureValidator().validate(payload);

        assertTrue(violations.isEmpty(), "v13 清单结构校验失败: " + violations);
        assertEquals(1, payload.getScenarios().size());
        ManifestPayload.ScenarioMeta scenario = payload.getScenarios().get(0);
        assertFalse(scenario.isLegacyShape(), "v13 必须是完整模型，不得走 legacy 分支");
        assertEquals("project-team", scenario.getOwner());
        assertEquals("1.0.0", scenario.getVersion());
        assertEquals("published", scenario.getStatus());
        assertEquals("domain.trade", scenario.getDomainId());
        assertEquals(List.of("concept.order", "concept.customer", "concept.channel", "concept.event"),
                scenario.getOntologyScope().getRootConcepts());
        assertEquals(3, scenario.getOntologyScope().getMaxPathHops());
        assertEquals("metric.gmv", scenario.getSemanticScope().getFrozenCalibers()
                .get("revenue_metric").getValue());
        assertEquals("dim.order_date", scenario.getSemanticScope().getFrozenCalibers()
                .get("default_time_field").getValue());
        assertEquals("block", scenario.getInvalidation().getOnScopeAssetDeprecation());
        assertTrue(scenario.getColdStartSamples().isEmpty(),
                "首版无已发布 VQR / Golden 引用，冷启动样例必须为空");
    }

    @Test
    void shouldAcceptV14ManifestWithSevenPublishedSkills() throws Exception {
        // M6/T3.1：Skill 资产进清单（首发 7 条内置，覆盖全部非 discovery 模式）
        ManifestPayload payload = load("first-release-v14.json");
        List<ManifestStructureValidator.Violation> violations =
                new ManifestStructureValidator().validate(payload);

        assertTrue(violations.isEmpty(), "v14 清单结构校验失败: " + violations);
        assertEquals(7, payload.getSkills().size());
        var skillIds = payload.getSkills().stream()
                .map(ManifestPayload.SkillMeta::getSkillId).toList();
        assertTrue(skillIds.contains("skill.entity_lookup"),
                "M3 entity_lookup 迁移注册制，既有 skillId 保留");
        // 每条 published Skill 必有审核人（生命周期 + T3.1 审核记录）
        assertTrue(payload.getSkills().stream().allMatch(
                skill -> "project-team".equals(skill.getReviewer())));
        // batch 有上界；attribution 绑定首发场景
        var batch = payload.getSkills().stream()
                .filter(skill -> "LOOKUP_BATCH".equals(skill.getAnalysisMode())).findFirst().orElseThrow();
        assertEquals(100, batch.getMaxBatchSize());
        var attribution = payload.getSkills().stream()
                .filter(skill -> "ATTRIBUTION".equals(skill.getAnalysisMode())).findFirst().orElseThrow();
        assertEquals(List.of("scenario.sales"), attribution.getAllowedScenarioIds());
    }

    @Test
    void shouldRoundTripV14SkillWithoutDroppingLifecycleKeys() throws Exception {
        String json = Files.readString(Path.of("..", "..", "ontos-assets", "first-release-v14.json"));
        java.util.Map<?, ?> submitted = JsonUtils.parseObject(json, java.util.Map.class);
        ManifestPayload typed = JsonUtils.parseObject(json, ManifestPayload.class);
        java.util.Map<?, ?> roundTrip = JsonUtils.parseObject(
                JsonUtils.toJsonString(typed), java.util.Map.class);

        java.util.Map<?, ?> submittedSkill = (java.util.Map<?, ?>) ((List<?>) submitted.get("skills")).get(0);
        java.util.Map<?, ?> roundTripSkill = (java.util.Map<?, ?>) ((List<?>) roundTrip.get("skills")).get(0);
        assertTrue(roundTripSkill.keySet().containsAll(submittedSkill.keySet()),
                "回装不得丢弃 Skill 生命周期字段: " + submittedSkill.keySet());
        assertTrue(roundTripSkill.containsKey("implementationRef"));
        assertTrue(roundTripSkill.containsKey("reviewer"));
        assertTrue(roundTripSkill.containsKey("maxBatchSize"));
    }

    @Test
    void shouldRoundTripV13ScenarioWithoutDroppingLifecycleKeys() throws Exception {
        String json = Files.readString(Path.of("..", "..", "ontos-assets", "first-release-v13.json"));
        java.util.Map<?, ?> submitted = JsonUtils.parseObject(json, java.util.Map.class);
        ManifestPayload typed = JsonUtils.parseObject(json, ManifestPayload.class);
        java.util.Map<?, ?> roundTrip = JsonUtils.parseObject(
                JsonUtils.toJsonString(typed), java.util.Map.class);

        java.util.Map<?, ?> submittedScenario = (java.util.Map<?, ?>) ((List<?>) submitted.get("scenarios")).get(0);
        java.util.Map<?, ?> roundTripScenario = (java.util.Map<?, ?>) ((List<?>) roundTrip.get("scenarios")).get(0);
        assertTrue(roundTripScenario.keySet().containsAll(submittedScenario.keySet()),
                "回装不得丢弃 Scenario 生命周期字段: " + submittedScenario.keySet());
        assertTrue(((java.util.Map<?, ?>) roundTripScenario.get("ontologyScope")).containsKey("rootConcepts"));
        assertTrue(((java.util.Map<?, ?>) roundTripScenario.get("semanticScope")).containsKey("frozenCalibers"));
    }

    @Test
    void shouldRoundTripV11WithoutDroppingChannelPropertyKey() throws Exception {
        // 发布链路未知字段键差分：channelPropertyId 若被 Jackson 静默丢弃，v11 发布会被拒绝
        String json = Files.readString(Path.of("..", "..", "ontos-assets", "first-release-v11.json"));
        java.util.Map<?, ?> submitted = JsonUtils.parseObject(json, java.util.Map.class);
        ManifestPayload typed = JsonUtils.parseObject(json, ManifestPayload.class);
        java.util.Map<?, ?> roundTrip = JsonUtils.parseObject(
                JsonUtils.toJsonString(typed), java.util.Map.class);

        List<?> submittedEvents = (List<?>) ((java.util.Map<?, ?>) submitted.get("ontology")).get("events");
        List<?> roundTripEvents = (List<?>) ((java.util.Map<?, ?>) roundTrip.get("ontology")).get("events");
        assertEquals(submittedEvents.size(), roundTripEvents.size());
        for (int i = 0; i < submittedEvents.size(); i++) {
            // 断言方向 = 提交键不丢（回装可为新增可选字段补 null 键，见 v2 同款说明）
            assertTrue(roundTripEvents.get(i) instanceof java.util.Map<?, ?> map
                    && map.keySet().containsAll(((java.util.Map<?, ?>) submittedEvents.get(i)).keySet()),
                    "回装不得丢弃事件字段（含 channelPropertyId）");
        }
        // channel_touch 声明的渠道映射必须带着值回装（不是 null）
        OntologySnapshot.EventDef touch = typed.getOntology().getEvents().stream()
                .filter(event -> "event.channel_touch".equals(event.getId()))
                .findFirst().orElseThrow(() -> new AssertionError("回装缺少渠道触点事件"));
        assertEquals("property.event.channel_id", touch.getChannelPropertyId());
    }

}
