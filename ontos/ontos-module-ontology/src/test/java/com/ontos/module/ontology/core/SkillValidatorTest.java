package com.ontos.module.ontology.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill 资产校验（v1.4 §5.8，M6/W3/T3.1）：生命周期必填、implementation_ref 命名空间、
 * allowed_scenario_ids 已发布引用、approximation_of 跨模式、published 必有审核人。
 */
class SkillValidatorTest {

    private final SkillValidator validator = new SkillValidator();

    @Test
    void validSkillPassesWithNoViolations() {
        assertTrue(validator.validate(payload(skill("skill.query.basic", "QUERY",
                "builtin.QUERY.basic@1.0.0", "published", "reviewer-a", null))).isEmpty());
    }

    @Test
    void publishedWithoutReviewerIsRejected() {
        List<ManifestStructureValidator.Violation> violations = validator.validate(
                payload(skill("skill.query.basic", "QUERY", "builtin.QUERY.basic@1.0.0",
                        "published", null, null)));
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("审核人")),
                "published 必须有审核人记录");
    }

    @Test
    void implementationRefNamespaceMismatchIsRejected() {
        // builtin.LOOKUP.* 但 analysisMode=QUERY：命名空间 mode 段与声明模式不一致
        List<ManifestStructureValidator.Violation> violations = validator.validate(
                payload(skill("skill.query.basic", "QUERY", "builtin.LOOKUP.entity@1.0.0",
                        "published", "reviewer-a", null)));
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("不一致")));
    }

    @Test
    void nonBuiltinImplementationRefIsRejected() {
        List<ManifestStructureValidator.Violation> violations = validator.validate(
                payload(skill("skill.query.basic", "QUERY", "custom.sql.free@1.0.0",
                        "published", "reviewer-a", null)));
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("builtin")));
    }

    @Test
    void allowedScenarioIdsMustReferencePublishedScenario() {
        ManifestPayload.SkillMeta skill = skill("skill.query.basic", "QUERY",
                "builtin.QUERY.basic@1.0.0", "published", "reviewer-a",
                List.of("scenario.unknown"));
        ManifestPayload payload = payload(skill);
        payload.setScenarios(List.of());
        List<ManifestStructureValidator.Violation> violations = validator.validate(payload);
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("未发布场景")));
    }

    @Test
    void approximationOfMustBeCrossMode() {
        ManifestPayload.SkillMeta near = skill("skill.query.approx", "QUERY",
                "builtin.QUERY.approx@1.0.0", "published", "reviewer-a", null);
        near.setApproximationOf("skill.query.basic");
        List<ManifestStructureValidator.Violation> violations = validator.validate(
                payload(near, skill("skill.query.basic", "QUERY",
                        "builtin.QUERY.basic@1.0.0", "published", "reviewer-a", null)));
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("跨模式")));
    }

    @Test
    void approximationOfToUnknownSkillIsRejected() {
        ManifestPayload.SkillMeta near = skill("skill.funnel.approx", "FUNNEL",
                "builtin.FUNNEL.approx@1.0.0", "published", "reviewer-a", null);
        near.setApproximationOf("skill.ghost");
        List<ManifestStructureValidator.Violation> violations = validator.validate(payload(near));
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("不在清单")));
    }

    @Test
    void discoveryModeIsNotSkillRegistrable() {
        List<ManifestStructureValidator.Violation> violations = validator.validate(
                payload(skill("skill.discovery.x", "DISCOVERY", "builtin.DISCOVERY.x@1.0.0",
                        "published", "reviewer-a", null)));
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("analysisMode")));
    }

    @Test
    void duplicateSkillIdIsRejected() {
        List<ManifestStructureValidator.Violation> violations = validator.validate(payload(
                skill("skill.query.basic", "QUERY", "builtin.QUERY.a@1.0.0", "published", "r", null),
                skill("skill.query.basic", "QUERY", "builtin.QUERY.b@1.0.0", "published", "r", null)));
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("重复")));
    }

    // ---------- fixtures ----------

    private static ManifestPayload.SkillMeta skill(String id, String mode, String implRef,
                                                   String status, String reviewer,
                                                   List<String> allowedScenarios) {
        ManifestPayload.SkillMeta skill = new ManifestPayload.SkillMeta();
        skill.setSkillId(id);
        skill.setName(id);
        skill.setOwner("platform-data");
        skill.setVersion("1.0.0");
        skill.setStatus(status);
        skill.setAnalysisMode(mode);
        skill.setImplementationRef(implRef);
        skill.setAllowedScenarioIds(allowedScenarios == null ? List.of() : allowedScenarios);
        skill.setReviewer(reviewer);
        return skill;
    }

    private static ManifestPayload payload(ManifestPayload.SkillMeta... skills) {
        ManifestPayload payload = new ManifestPayload();
        payload.setSkills(List.of(skills));
        payload.setScenarios(List.of());
        return payload;
    }
}
