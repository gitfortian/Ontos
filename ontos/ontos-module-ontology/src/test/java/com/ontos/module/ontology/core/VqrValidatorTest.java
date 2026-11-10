package com.ontos.module.ontology.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ontos.module.ontology.core.ManifestStructureValidator.Violation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VQR 资产校验（M6/W3/T3.4，v1.4 §11.2 入库条件的清单侧可判定子集）。 */
class VqrValidatorTest {

    private final VqrValidator validator = new VqrValidator();

    @Test
    void publishedVqrWithCompleteEvidencePasses() {
        List<Violation> violations = validator.validateOne(validPublished(), payload());
        assertTrue(violations.isEmpty(), "完整 VQR 应通过: " + violations);
    }

    @Test
    void structureOnlyValidationSkipsAssetBinding() {
        // 草稿创建期：模板引用的资产可以尚未进清单（条件 2 留待发布闸门）
        ManifestPayload.VqrMeta vqr = validPublished();
        List<Violation> violations = validator.validateStructure(vqr);
        assertTrue(violations.isEmpty(), "结构校验不做绑定: " + violations);
    }

    @Test
    void placeholderMustBeDeclaredInParamsSchema() {
        ManifestPayload.VqrMeta vqr = validPublished();
        vqr.getDslTemplate().put("limit", "${param.rows}");
        List<Violation> violations = validator.validateOne(vqr, payload());
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("占位符未在 paramsSchema 声明")),
                "未知占位符 = 绑定期无法取值: " + violations);
    }

    @Test
    void assetReferenceMissingFromTargetFailsBinding() {
        List<Violation> violations = validator.validateOne(validPublished(), emptyPayload());
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("引用资产不存在或未发布")),
                "条件 2 绑定稳定按目标清单判定: " + violations);
    }

    @Test
    void publishedRequiresEvidenceRefs() {
        ManifestPayload.VqrMeta vqr = validPublished();
        vqr.setGoldenRef(null);
        vqr.setResultCheckRef(" ");
        List<Violation> violations = validator.validateOne(vqr, payload());
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("条件 3")),
                "published 必须携带 Golden 证据引用: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("条件 4-6")),
                "published 必须携带结果校验证据引用（D4 外置）: " + violations);
    }

    @Test
    void publishedRequiresFreshnessAndInvalidation() {
        ManifestPayload.VqrMeta vqr = validPublished();
        vqr.setFreshnessLimitHours(0);
        vqr.setInvalidationConditions(List.of());
        List<Violation> violations = validator.validateOne(vqr, payload());
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("freshnessLimitHours")),
                "新鲜度上限必填: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("invalidationConditions")),
                "失效条件必填: " + violations);
    }

    @Test
    void invalidationConditionMustReferenceOwnDependency() {
        ManifestPayload.VqrMeta vqr = validPublished();
        vqr.setInvalidationConditions(List.of(Map.of("assetId", "metric.distinct_customers",
                "trigger", "deprecated")));
        List<Violation> violations = validator.validateOne(vqr, payload());
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("不在 VQR 依赖集内")),
                "死声明（依赖集外）拦截: " + violations);
    }

    @Test
    void invalidationTriggerIsClosedVocabulary() {
        ManifestPayload.VqrMeta vqr = validPublished();
        vqr.setInvalidationConditions(List.of(Map.of("assetId", "metric.gmv", "trigger", "whenever")));
        List<Violation> violations = validator.validateOne(vqr, payload());
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("trigger 非法")),
                "trigger 闭枚举: " + violations);
    }

    @Test
    void draftDoesNotRequireEvidenceRefs() {
        ManifestPayload.VqrMeta vqr = validPublished();
        vqr.setStatus("draft");
        vqr.setGoldenRef(null);
        vqr.setResultCheckRef(null);
        vqr.setFreshnessLimitHours(null);
        vqr.setInvalidationConditions(List.of());
        List<Violation> violations = validator.validateOne(vqr, payload());
        assertTrue(violations.isEmpty(), "draft 只做结构校验: " + violations);
    }

    @Test
    void publishedVqrMakesScenarioDefaultVqrIdsResolvable() {
        // T3.4：vqrs 段落地后，ScenarioValidator 的 defaultVqrIds/coldStartSamples 引用可解析
        ManifestPayload payload = payload();
        payload.setVqrs(new ArrayList<>(List.of(validPublished())));
        payload.getScenarios().get(0).setDefaultVqrIds(List.of("vqr.sales.daily_gmv_by_channel"));
        payload.getScenarios().get(0).setColdStartSamples(List.of("vqr.sales.daily_gmv_by_channel"));
        List<Violation> violations = new ManifestStructureValidator().validate(payload);
        assertTrue(violations.stream().noneMatch(v -> v.message().contains("defaultVqrIds")
                        || v.message().contains("coldStartSamples")),
                "场景引用已发布 VQR 应可解析（最小夹具无关违规忽略）: " + violations);

        // 引用未发布的 VQR → 阻断
        payload.getVqrs().get(0).setStatus("draft");
        violations = new ManifestStructureValidator().validate(payload);
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("只能引用已发布 VQR")),
                "defaultVqrIds 引用未发布 VQR 阻断: " + violations);
    }

    // ---------- fixtures ----------

    private static ManifestPayload.VqrMeta validPublished() {
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
        vqr.setParamsSchema(Map.of("days", Map.of("type", "integer", "required", true)));
        vqr.setGoldenRef("golden:m6-golden-v1#golden-gmv-by-channel");
        vqr.setResultCheckRef("e2e:t24-e2e#direct-gmv-query-compiled");
        vqr.setFreshnessLimitHours(24);
        vqr.setInvalidationConditions(List.of(
                Map.of("assetId", "metric.gmv", "trigger", "deprecated"),
                Map.of("assetId", "dim.channel", "trigger", "incompatible_change")));
        vqr.setReviewer("project-team");
        vqr.setReviewedAt("2026-11-10");
        return vqr;
    }

    private static ManifestPayload payload() {
        ManifestPayload payload = new ManifestPayload();
        SemanticManifest semantic = new SemanticManifest();
        SemanticManifest.Metric gmv = new SemanticManifest.Metric();
        gmv.setId("metric.gmv");
        SemanticManifest.Dimension channel = new SemanticManifest.Dimension();
        channel.setId("dim.channel");
        semantic.setMetrics(new ArrayList<>(List.of(gmv)));
        semantic.setDimensions(new ArrayList<>(List.of(channel)));
        payload.setSemantic(semantic);
        ManifestPayload.ScenarioMeta scenario = new ManifestPayload.ScenarioMeta();
        scenario.setId("scenario.sales");
        scenario.setStatus("published");
        scenario.setAllowedAnalysisModes(List.of("query"));
        payload.setScenarios(new ArrayList<>(List.of(scenario)));
        return payload;
    }

    private static ManifestPayload emptyPayload() {
        return new ManifestPayload();
    }
}
