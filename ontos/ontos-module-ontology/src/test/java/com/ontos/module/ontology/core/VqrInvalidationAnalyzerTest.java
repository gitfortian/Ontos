package com.ontos.module.ontology.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VQR 依赖影响分析 / 自动失效（M6/W3/T3.4，v1.4 §11.3 + F-7 传播口径）——失效用例。 */
class VqrInvalidationAnalyzerTest {

    private final VqrInvalidationAnalyzer analyzer = new VqrInvalidationAnalyzer();

    @Test
    void deprecatedAssetInvalidatesVqr() {
        // dim.channel 在新清单中消失 → VQR 失效（依赖资产废弃）
        List<VqrInvalidationAnalyzer.Invalidation> result =
                analyzer.analyze(oldPayload(), newPayloadWithoutChannel());
        assertEquals(1, result.size());
        assertEquals("vqr.sales.daily_gmv_by_channel", result.get(0).vqrId());
        assertTrue(result.get(0).reason().contains("依赖资产已废弃: dim.channel"));
    }

    @Test
    void incompatibleMetricDefinitionChangeInvalidatesVqr() {
        // metric.gmv 的聚合表达式在新清单中变化（sum → count）→ 不兼容变更
        ManifestPayload newPayload = newPayload();
        newPayload.getSemantic().getMetrics().stream()
                .filter(metric -> "metric.gmv".equals(metric.getId()))
                .findFirst().orElseThrow()
                .setColumn("order.amount_v2");
        List<VqrInvalidationAnalyzer.Invalidation> result =
                analyzer.analyze(oldPayload(), newPayload);
        assertEquals(1, result.size());
        assertTrue(result.get(0).reason().contains("依赖资产定义发生不兼容变更: metric.gmv"),
                "语义定义变化 = 不兼容（保守判定）: " + result.get(0).reason());
    }

    @Test
    void ruleLosingPublishedStatusInvalidatesVqr() {
        // VQR 依赖 rule.high_value（模板引用）；新清单中该规则退回 draft
        ManifestPayload newPayload = newPayloadWithRuleDependency();
        newPayload.getRules().forEach(rule -> rule.setStatus("draft"));
        List<VqrInvalidationAnalyzer.Invalidation> result =
                analyzer.analyze(oldPayloadWithRule(), newPayload);
        assertEquals(1, result.size());
        assertTrue(result.get(0).reason().contains("依赖 Rule 不再 published"),
                "依赖 Rule 失效 → VQR 失效: " + result.get(0).reason());
    }

    @Test
    void skillMajorVersionBumpInvalidatesVqr() {
        ManifestPayload oldPayload = oldPayloadWithSkill();
        ManifestPayload newPayload = newPayloadWithSkill();
        newPayload.getSkills().forEach(skill -> skill.setVersion("2.0.0"));
        List<VqrInvalidationAnalyzer.Invalidation> result = analyzer.analyze(oldPayload, newPayload);
        assertTrue(result.stream().anyMatch(i -> i.reason().contains("依赖 Skill 发生不兼容变更")),
                "主版本升级 = 不兼容: " + result);
    }

    @Test
    void scenarioModeNarrowingInvalidatesReferencedVqr() {
        // 场景 allowed_analysis_modes 收窄为 yoy_mom，不再包含模板 mode=query
        ManifestPayload newPayload = newPayload();
        newPayload.getScenarios().get(0).setAllowedAnalysisModes(List.of("yoy_mom"));
        List<VqrInvalidationAnalyzer.Invalidation> result = analyzer.analyze(oldPayload(), newPayload);
        assertEquals(1, result.size());
        assertTrue(result.get(0).reason().contains("allowed_analysis_modes"),
                "引用场景模式收窄: " + result.get(0).reason());
    }

    @Test
    void declaredConditionHitInvalidatesVqr() {
        // 声明式条件 {metric.gmv, incompatible_change} 命中
        ManifestPayload newPayload = newPayload();
        newPayload.getSemantic().getMetrics().stream()
                .filter(metric -> "metric.gmv".equals(metric.getId()))
                .findFirst().orElseThrow()
                .setColumn("order.amount_v2");
        List<VqrInvalidationAnalyzer.Invalidation> result = analyzer.analyze(oldPayload(), newPayload);
        assertEquals(1, result.size());
        assertTrue(result.get(0).reason().contains("声明失效条件命中"),
                "声明式失效条件消费: " + result.get(0).reason());
    }

    @Test
    void applyRemovesVqrAndCascadesScenarioReferences() {
        // T34-8：失效生效 = 移除 VQR + 级联清理场景 defaultVqrIds/coldStartSamples——
        // 悬空引用会把后续发布卡死在「只能引用已发布 VQR」
        ManifestPayload payload = newPayload();
        // 制造失效：metric.gmv 物理列变更（不兼容变更族）
        payload.getSemantic().getMetrics().stream()
                .filter(metric -> "metric.gmv".equals(metric.getId()))
                .findFirst().orElseThrow()
                .setColumn("order.paid_amount_v2");
        List<VqrInvalidationAnalyzer.Invalidation> invalidations =
                analyzer.analyze(oldPayload(), payload);
        assertEquals(1, invalidations.size());

        int cascadeRemoved = analyzer.apply(payload, invalidations);
        assertEquals(2, cascadeRemoved, "defaultVqrIds + coldStartSamples 各 1 条");
        assertTrue(payload.getVqrs().isEmpty(), "失效 VQR 从新清单移除");
        assertTrue(payload.getScenarios().get(0).getDefaultVqrIds().isEmpty(),
                "场景引用级联清理");
        assertTrue(payload.getScenarios().get(0).getColdStartSamples().isEmpty(),
                "冷启动样例级联清理");
        // 级联清理后重新校验：不再有 VQR 悬空引用违规
        assertTrue(new ManifestStructureValidator().validate(payload).stream()
                        .noneMatch(v -> v.message().contains("只能引用已发布 VQR")),
                "级联清理后无悬空引用");
    }

    @Test
    void unchangedManifestProducesNoInvalidation() {
        assertTrue(analyzer.analyze(oldPayload(), newPayload()).isEmpty(),
                "无变化 = 零失效");
    }

    @Test
    void firstPublishSkipsOldComparison() {
        // 首次发布（无旧清单）：无新旧对比失效（自洽性由 VqrValidator 闸门负责）
        assertTrue(analyzer.analyze(null, newPayload()).isEmpty(),
                "首次发布不做新旧对比");
    }

    // ---------- fixtures ----------

    private static ManifestPayload.VqrMeta vqr() {
        ManifestPayload.VqrMeta vqr = new ManifestPayload.VqrMeta();
        vqr.setVqrId("vqr.sales.daily_gmv_by_channel");
        vqr.setName("近 N 天各渠道成交金额");
        vqr.setOwner("platform-data");
        vqr.setVersion("1.0.0");
        vqr.setStatus("published");
        vqr.setQuestionPattern("近{days}天各渠道成交金额");
        vqr.setDslTemplate(new java.util.HashMap<>(Map.of(
                "mode", "query",
                "metric_id", "metric.gmv",
                "dimensions", List.of("dim.channel"),
                "time_expression_raw", "近 ${param.days} 天")));
        vqr.setParamsSchema(Map.of("days", Map.of("type", "integer")));
        vqr.setGoldenRef("golden:x");
        vqr.setResultCheckRef("e2e:x");
        vqr.setFreshnessLimitHours(24);
        vqr.setInvalidationConditions(List.of(
                Map.of("assetId", "metric.gmv", "trigger", "incompatible_change")));
        vqr.setReviewer("project-team");
        return vqr;
    }

    private static void seedSemantic(ManifestPayload payload, String aggregate) {
        SemanticManifest semantic = new SemanticManifest();
        SemanticManifest.Metric gmv = new SemanticManifest.Metric();
        gmv.setId("metric.gmv");
        gmv.setColumn(aggregate.equals("sum") ? "order.paid_amount" : "order.paid_amount_changed");
        SemanticManifest.Dimension channel = new SemanticManifest.Dimension();
        channel.setId("dim.channel");
        semantic.setMetrics(new ArrayList<>(List.of(gmv)));
        semantic.setDimensions(new ArrayList<>(List.of(channel)));
        payload.setSemantic(semantic);
    }

    private static ManifestPayload basePayload() {
        ManifestPayload payload = new ManifestPayload();
        seedSemantic(payload, "sum");
        ManifestPayload.ScenarioMeta scenario = new ManifestPayload.ScenarioMeta();
        scenario.setId("scenario.sales");
        scenario.setStatus("published");
        scenario.setAllowedAnalysisModes(List.of("query"));
        scenario.setDefaultVqrIds(List.of("vqr.sales.daily_gmv_by_channel"));
        scenario.setColdStartSamples(List.of("vqr.sales.daily_gmv_by_channel"));
        payload.setScenarios(new ArrayList<>(List.of(scenario)));
        payload.setVqrs(new ArrayList<>(List.of(vqr())));
        return payload;
    }

    private static ManifestPayload oldPayload() {
        return basePayload();
    }

    private static ManifestPayload newPayload() {
        return basePayload();
    }

    private static ManifestPayload newPayloadWithoutChannel() {
        ManifestPayload payload = basePayload();
        payload.getVqrs().clear();
        payload.getSemantic().setDimensions(new ArrayList<>());
        payload.setVqrs(new ArrayList<>(List.of(vqr())));
        return payload;
    }

    private static ManifestPayload oldPayloadWithRule() {
        ManifestPayload payload = basePayload();
        ManifestPayload.Rule rule = new ManifestPayload.Rule();
        rule.setId("rule.high_value");
        rule.setStatus("published");
        payload.setRules(new ArrayList<>(List.of(rule)));
        payload.getVqrs().get(0).getDslTemplate().put("boolean_metric_ref", "rule.high_value");
        return payload;
    }

    private static ManifestPayload newPayloadWithRuleDependency() {
        return oldPayloadWithRule();
    }

    private static ManifestPayload oldPayloadWithSkill() {
        ManifestPayload payload = basePayload();
        ManifestPayload.SkillMeta skill = new ManifestPayload.SkillMeta();
        skill.setSkillId("skill.query.basic");
        skill.setStatus("published");
        skill.setVersion("1.0.0");
        skill.setAnalysisMode("QUERY");
        payload.setSkills(new ArrayList<>(List.of(skill)));
        payload.getVqrs().get(0).getDslTemplate().put("skill_ref", "skill.query.basic");
        return payload;
    }

    private static ManifestPayload newPayloadWithSkill() {
        return oldPayloadWithSkill();
    }
}
