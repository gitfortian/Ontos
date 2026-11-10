package com.ontos.module.query.core.skill;

import java.util.List;
import java.util.Map;

import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** M3/W3 建立、M6/W3/T3.2 升级清单驱动注册制：published 解析、未发布同形、carriersFor 三过滤。 */
class SkillRegistryTest {

    private final ManifestService manifestService = mock(ManifestService.class);

    @Test
    void shouldListAndResolveHandlersFromManifest() {
        when(manifestService.getActiveManifestOrNull()).thenReturn(manifest());
        SkillRegistry registry = registry();

        // list() 兼容纪律：只列可执行能力（M3 契约不回退；T3.3 新增 QuerySkill 后共 2 条）
        assertEquals(2, registry.list().size());
        assertEquals("entity_lookup", registry.list().get(0).skillId());
        assertEquals("skill.query.approx", registry.list().get(1).skillId());
        // 清单驱动解析：published + implementation_ref 命名空间
        assertTrue(registry.byId("entity_lookup").isPresent());
        assertTrue(registry.byId("nope").isEmpty(), "未发布 / 不存在同形 empty（不泄露存在性）");
    }

    @Test
    void skillRegisteredWithoutBuiltinMappingIsRejected() {
        when(manifestService.getActiveManifestOrNull()).thenReturn(manifest());
        SkillRegistry registry = registry();
        // 清单注册了 builtin.FUNNEL.standard（无内置映射）→ fail-closed 拒绝执行
        assertTrue(registry.byId("skill.funnel.standard").isEmpty(),
                "注册但无映射 = 拒绝执行，不假装支持");
    }

    @Test
    void carriersForFiltersByModeCapabilityAndScenario() {
        when(manifestService.getActiveManifestOrNull()).thenReturn(manifest());
        SkillRegistry registry = registry();

        // 模式匹配 + capabilities 满足 + 全局（空 allowed）
        assertEquals(1, registry.carriersFor("LOOKUP", null).size());
        // allowed_scenario_ids 非空且当前场景不在列 → 不承担
        assertEquals(0, registry.carriersFor("ATTRIBUTION", "scenario.other").size(),
                "非空白名单要求当前场景在列");
        // attribution 的 capabilities 只有 semantic.query（ruleManifest 已发布规则 → rule.evaluate 满足）
        assertEquals(1, registry.carriersFor("ATTRIBUTION", "scenario.sales").size());
    }

    @Test
    void carriersForEmptyWhenRuleCenterLacksPublishedRule() {
        PublishedManifest manifest = manifest();
        manifest.getRules().forEach(rule -> rule.setStatus("draft"));
        when(manifestService.getActiveManifestOrNull()).thenReturn(manifest);
        SkillRegistry registry = registry();

        // entity_lookup 需要 rule.evaluate——规则未发布 → 能力不满足 → 无承担者
        assertEquals(0, registry.carriersFor("LOOKUP", null).size(),
                "rule.evaluate 要求 Rule 注册中心可用且规则已发布");
    }

    @Test
    void registrationModeDistinguishedBySkillsSectionPresence() {
        // T32-6：有 skills 段 = 严格注册制；无段 = M3 直挂兼容态
        PublishedManifest withSection = manifest();
        when(manifestService.getActiveManifestOrNull()).thenReturn(withSection);
        assertTrue(registry().isRegistrationMode(withSection), "有 skills 段 → 注册制");

        PublishedManifest legacyManifest = new PublishedManifest();
        legacyManifest.setManifestId("legacy");
        when(manifestService.getActiveManifestOrNull()).thenReturn(legacyManifest);
        org.junit.jupiter.api.Assertions.assertFalse(registry().isRegistrationMode(legacyManifest),
                "无 skills 段 → 兼容态");
        assertTrue(registry().legacyById("entity_lookup").isPresent(),
                "兼容态直挂查询命中（全量 skillId 视图）");
    }

    @Test
    void approximationCarriersRequireCrossModeDeclarationAndExecutableHandler() {
        // T3.3：近似承担者 = published + 可执行 + approximationOf 指向被拒模式 + 场景在列
        when(manifestService.getActiveManifestOrNull()).thenReturn(manifestWithApproximation());
        SkillRegistry registry = registry();

        // FUNNEL 被场景拒绝 → skill.query.approx 声明近似 funnel 且 QUERY handler 可解析
        var carriers = registry.approximationCarriersFor("FUNNEL", "scenario.sales");
        assertEquals(1, carriers.size());
        assertEquals("skill.query.approx", carriers.get(0).skill().getSkillId());
        assertEquals("funnel", carriers.get(0).approximatedMode());

        // 同模式自我近似不成立：REQUEST 模式=QUERY 有承担者，近似声明指向 QUERY 的 Skill 不匹配
        assertEquals(0, registry.approximationCarriersFor("QUERY", "scenario.sales").size());
        // 场景外不承担
        assertEquals(0, registry.approximationCarriersFor("FUNNEL", "scenario.other").size());
    }

    @Test
    void approximationCarrierWithoutExecutableHandlerIsRejected() {
        // 近似承担者的 implementation_ref 无内置映射 → 不可执行 → 不承担（fail-closed）
        PublishedManifest manifest = manifestWithApproximation();
        manifest.getSkills().stream()
                .filter(skill -> "skill.query.approx".equals(skill.getSkillId()))
                .findFirst().orElseThrow()
                .setImplementationRef("builtin.QUERY.ghost@1.0.0");
        when(manifestService.getActiveManifestOrNull()).thenReturn(manifest);
        assertEquals(0, registry().approximationCarriersFor("FUNNEL", "scenario.sales").size(),
                "不可执行的近似承担者不参与降级");
    }

    private PublishedManifest manifestWithApproximation() {
        PublishedManifest manifest = manifest();
        ManifestPayload.SkillMeta approx = new ManifestPayload.SkillMeta();
        approx.setSkillId("skill.query.approx");
        approx.setOwner("platform-data");
        approx.setVersion("1.0.0");
        approx.setStatus("published");
        approx.setAnalysisMode("QUERY");
        approx.setImplementationRef("builtin.QUERY.approx@1.0.0");
        approx.setRequiredCapabilities(List.of("semantic.query"));
        approx.setAllowedScenarioIds(List.of("scenario.sales"));
        approx.setApproximationOf("skill.funnel.standard");
        approx.setReviewer("project-team");
        List<ManifestPayload.SkillMeta> skills = new java.util.ArrayList<>(manifest.getSkills());
        skills.add(approx);
        manifest.setSkills(skills);
        // 近似样例需要一个 QUERY 可执行 handler（QuerySkill 声明 builtin.QUERY.approx）
        return manifest;
    }

    private SkillRegistry registry() {
        return new SkillRegistry(List.of(new EntityLookupSkill(), new QuerySkill()), manifestService);
    }

    private static PublishedManifest manifest() {
        PublishedManifest manifest = new PublishedManifest();
        manifest.setManifestId("manifest-001");

        ManifestPayload.SkillMeta lookup = new ManifestPayload.SkillMeta();
        lookup.setSkillId("entity_lookup");
        lookup.setOwner("platform-data");
        lookup.setVersion("1.1.0");
        lookup.setStatus("published");
        lookup.setAnalysisMode("LOOKUP");
        lookup.setImplementationRef("builtin.LOOKUP.entity@1.1.0");
        lookup.setRequiredCapabilities(List.of("semantic.query", "rule.evaluate"));
        lookup.setAllowedScenarioIds(List.of());
        lookup.setReviewer("project-team");

        ManifestPayload.SkillMeta attribution = new ManifestPayload.SkillMeta();
        attribution.setSkillId("skill.attribution.last_touch");
        attribution.setOwner("platform-data");
        attribution.setVersion("1.0.0");
        attribution.setStatus("published");
        attribution.setAnalysisMode("ATTRIBUTION");
        attribution.setImplementationRef("builtin.ATTRIBUTION.last_touch@1.0.0");
        attribution.setRequiredCapabilities(List.of("semantic.query"));
        attribution.setAllowedScenarioIds(List.of("scenario.sales"));
        attribution.setReviewer("project-team");

        ManifestPayload.SkillMeta funnelNoMapping = new ManifestPayload.SkillMeta();
        funnelNoMapping.setSkillId("skill.funnel.standard");
        funnelNoMapping.setOwner("platform-data");
        funnelNoMapping.setVersion("1.0.0");
        funnelNoMapping.setStatus("published");
        funnelNoMapping.setAnalysisMode("FUNNEL");
        funnelNoMapping.setImplementationRef("builtin.FUNNEL.standard@1.0.0");
        funnelNoMapping.setRequiredCapabilities(List.of("semantic.query"));
        funnelNoMapping.setReviewer("project-team");

        ManifestPayload.SkillMeta draft = new ManifestPayload.SkillMeta();
        draft.setSkillId("skill.query.draft");
        draft.setOwner("platform-data");
        draft.setVersion("1.0.0");
        draft.setStatus("draft");
        draft.setAnalysisMode("QUERY");
        draft.setImplementationRef("builtin.QUERY.draft@1.0.0");
        draft.setReviewer("project-team");

        manifest.setSkills(List.of(lookup, attribution, funnelNoMapping, draft));

        ManifestPayload.Rule rule = new ManifestPayload.Rule();
        rule.setId("rule.high_value_customer");
        rule.setStatus("published");
        manifest.setRules(List.of(rule));
        return manifest;
    }
}
