package com.ontos.module.ontology.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scenario 资产校验（M6/W2/T2.2，v1.4 §5.7 逐条）。
 *
 * <p>只做「结构与引用」校验，不执行路由、不做权限判定；发布预检（清单结构校验）
 * 与资产管理面的发布校验共用本类，避免两套规则漂移。
 *
 * <p>历史清单（v12 及以前）的 scope 是扁平数组，按 {@link ManifestPayload.ScenarioMeta#isLegacyShape()}
 * 走最小校验；v13 起为完整模型，逐条校验 owner/version/status/scope/calibers/skills/冷启动引用。
 */
public final class ScenarioValidator {

    private static final Set<String> ENTRY_MODES =
            Set.of("manual_only", "recommend_or_confirm", "auto_if_confident");
    private static final Set<String> STATUSES = Set.of("draft", "reviewing", "published", "retired");
    private static final Set<String> ANALYSIS_MODES = Set.of(
            "query", "lookup", "lookup_batch", "funnel", "yoy_mom", "attribution", "retention", "discovery");
    private static final Set<String> CALIBER_TYPES = Set.of("string", "metric_id", "field_id");
    private static final Set<String> INVALIDATION_ACTIONS = Set.of("block", "warn");
    private static final int MAX_PATH_HOPS_LIMIT = 5;
    private static final int MAX_ENTRY_HINTS = 50;

    /** 已发布 Skill / VQR / Golden Query 引用集合；首版 W2 为空，T3.x 起由清单扩展段填充。 */
    public record PublishedRefs(Set<String> skillIds, Set<String> vqrIds, Set<String> goldenQueryIds) {
        public static PublishedRefs empty() {
            return new PublishedRefs(Set.of(), Set.of(), Set.of());
        }
    }

    public List<ManifestStructureValidator.Violation> validate(ManifestPayload payload) {
        return validate(payload, PublishedRefs.empty());
    }

    public List<ManifestStructureValidator.Violation> validate(ManifestPayload payload, PublishedRefs refs) {
        List<ManifestStructureValidator.Violation> violations = new ArrayList<>();
        if (payload == null || payload.getScenarios() == null) {
            return violations;
        }
        Index index = new Index(payload);
        Set<String> ids = new HashSet<>();
        for (ManifestPayload.ScenarioMeta scenario : payload.getScenarios()) {
            String at = "scenario." + scenario.getId();
            if (scenario.getId() == null || scenario.getId().isBlank()) {
                violations.add(new ManifestStructureValidator.Violation("scenario", "Scenario ID 不能为空"));
                continue;
            }
            if (!ids.add(scenario.getId())) {
                violations.add(new ManifestStructureValidator.Violation(at, "Scenario ID 重复"));
            }
            violations.addAll(validateAgainst(scenario, index, refs));
        }
        return violations;
    }

    /** 单 Scenario 校验（资产管理面发布时对候选场景与目标清单复用同一套规则）。 */
    public List<ManifestStructureValidator.Violation> validateOne(ManifestPayload.ScenarioMeta scenario,
                                                                  ManifestPayload payload,
                                                                  PublishedRefs refs) {
        if (scenario == null) {
            return List.of(new ManifestStructureValidator.Violation("scenario", "Scenario 不能为空"));
        }
        if (scenario.getId() == null || scenario.getId().isBlank()) {
            return List.of(new ManifestStructureValidator.Violation("scenario", "Scenario ID 不能为空"));
        }
        return validateAgainst(scenario, new Index(payload), refs);
    }

    private List<ManifestStructureValidator.Violation> validateAgainst(ManifestPayload.ScenarioMeta scenario,
                                                                       Index index, PublishedRefs refs) {
        List<ManifestStructureValidator.Violation> violations = new ArrayList<>();
        String at = "scenario." + scenario.getId();
        if (scenario.isLegacyShape()) {
            validateLegacy(scenario, index, at, violations);
        } else {
            validateFull(scenario, index, refs, at, violations);
        }
        return violations;
    }

    private void validateLegacy(ManifestPayload.ScenarioMeta scenario, Index index, String at,
                                List<ManifestStructureValidator.Violation> violations) {
        requireText(scenario.getName(), at, "业务名 name 必填", violations);
        requireText(scenario.getSummary(), at, "一句话说明 summary 必填", violations);
        requireEnum(scenario.getEntryMode(), ENTRY_MODES, at, "entryMode 非法: ", violations);
        for (String conceptId : orEmpty(scenario.getOntologyScope().getRootConcepts())) {
            if (!index.conceptIds.contains(conceptId)) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "ontologyScope 引用不存在的业务对象: " + conceptId));
            }
        }
        for (String assetId : orEmpty(scenario.getSemanticScope().getMetrics())) {
            if (!index.metricIds.contains(assetId)) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "semanticScope 引用不存在的资产: " + assetId));
            }
        }
        for (String mode : orEmpty(scenario.getAllowedAnalysisModes())) {
            if (!ANALYSIS_MODES.contains(mode)) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "allowedAnalysisModes 不在封闭枚举内: " + mode));
            }
        }
    }

    private void validateFull(ManifestPayload.ScenarioMeta scenario, Index index, PublishedRefs refs,
                              String at, List<ManifestStructureValidator.Violation> violations) {
        requireText(scenario.getName(), at, "业务名 name 必填", violations);
        requireText(scenario.getSummary(), at, "一句话说明 summary 必填", violations);
        requireText(scenario.getOwner(), at, "owner 必填", violations);
        requireText(scenario.getVersion(), at, "version 必填", violations);
        requireText(scenario.getDomainId(), at, "domainId 必填", violations);
        requireEnum(scenario.getStatus(), STATUSES, at, "status 非法: ", violations);
        requireEnum(scenario.getEntryMode(), ENTRY_MODES, at, "entryMode 非法: ", violations);

        validateOntologyScope(scenario, index, at, violations);
        validateSemanticScope(scenario, index, at, violations);
        validateModesAndSkills(scenario, refs, at, violations);
        validateHints(scenario, at, violations);
        validateColdStart(scenario, refs, at, violations);
        validateVersionCompatibility(scenario, index, at, violations);
        validateInvalidation(scenario, at, violations);
    }

    private void validateOntologyScope(ManifestPayload.ScenarioMeta scenario, Index index, String at,
                                       List<ManifestStructureValidator.Violation> violations) {
        ManifestPayload.OntologyScope scope = scenario.getOntologyScope();
        List<String> roots = orEmpty(scope.getRootConcepts());
        if (roots.isEmpty()) {
            violations.add(new ManifestStructureValidator.Violation(at, "ontologyScope.rootConcepts 不能为空"));
        }
        Set<String> rootSet = new HashSet<>(roots);
        for (String conceptId : roots) {
            if (!index.conceptIds.contains(conceptId)) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "ontologyScope 引用不存在的业务对象: " + conceptId));
            }
        }
        for (String excluded : orEmpty(scope.getExcludedConcepts())) {
            if (!index.conceptIds.contains(excluded)) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "excludedConcepts 引用不存在的业务对象: " + excluded));
            }
            if (rootSet.contains(excluded)) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "excludedConcepts 不得与 rootConcepts 重叠: " + excluded));
            }
        }
        for (String relationId : orEmpty(scope.getAllowedRelations())) {
            OntologySnapshot.Relation relation = index.relationById.get(relationId);
            if (relation == null) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "allowedRelations 引用不存在的关系: " + relationId));
                continue;
            }
            if (!rootSet.contains(relation.getFrom()) || !rootSet.contains(relation.getTo())) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "allowedRelations 的端点必须落在 rootConcepts 内: " + relationId));
            }
        }
        if (scope.getMaxPathHops() != null
                && (scope.getMaxPathHops() < 1 || scope.getMaxPathHops() > MAX_PATH_HOPS_LIMIT)) {
            violations.add(new ManifestStructureValidator.Violation(at,
                    "maxPathHops 必须在 [1, " + MAX_PATH_HOPS_LIMIT + "]: " + scope.getMaxPathHops()));
        }
    }

    private void validateSemanticScope(ManifestPayload.ScenarioMeta scenario, Index index, String at,
                                       List<ManifestStructureValidator.Violation> violations) {
        ManifestPayload.SemanticScope scope = scenario.getSemanticScope();
        List<String> metrics = orEmpty(scope.getMetrics());
        List<String> dimensions = orEmpty(scope.getDimensions());
        if (metrics.isEmpty() && dimensions.isEmpty()) {
            violations.add(new ManifestStructureValidator.Violation(at,
                    "semanticScope 至少要声明一个指标或维度"));
        }
        for (String metricId : metrics) {
            if (!index.metricIds.contains(metricId)) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "semanticScope.metrics 引用不存在的指标: " + metricId));
            }
        }
        for (String dimensionId : dimensions) {
            if (!index.dimensionIds.contains(dimensionId)) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "semanticScope.dimensions 引用不存在的维度: " + dimensionId));
            }
        }
        scope.getFrozenCalibers().forEach((key, caliber) -> {
            if (key == null || key.isBlank()) {
                violations.add(new ManifestStructureValidator.Violation(at, "frozenCalibers 的 key 不能为空"));
                return;
            }
            String caliberAt = at + ".frozenCalibers." + key;
            if (caliber == null) {
                violations.add(new ManifestStructureValidator.Violation(caliberAt, "冻结口径不能为空"));
                return;
            }
            if (!CALIBER_TYPES.contains(caliber.getType())) {
                violations.add(new ManifestStructureValidator.Violation(caliberAt,
                        "type 只能是 string / metric_id / field_id: " + caliber.getType()));
                return;
            }
            if (caliber.getValue() == null || caliber.getValue().isBlank()) {
                violations.add(new ManifestStructureValidator.Violation(caliberAt, "value 不能为空"));
                return;
            }
            if ("metric_id".equals(caliber.getType()) && !index.metricIds.contains(caliber.getValue())) {
                violations.add(new ManifestStructureValidator.Violation(caliberAt,
                        "metric_id 引用未发布指标: " + caliber.getValue()));
            }
            if ("field_id".equals(caliber.getType())
                    && !index.dimensionIds.contains(caliber.getValue())
                    && !index.propertyIds.contains(caliber.getValue())) {
                violations.add(new ManifestStructureValidator.Violation(caliberAt,
                        "field_id 引用未发布字段: " + caliber.getValue()));
            }
        });
    }

    private void validateModesAndSkills(ManifestPayload.ScenarioMeta scenario, PublishedRefs refs, String at,
                                        List<ManifestStructureValidator.Violation> violations) {
        Set<String> modes = new HashSet<>();
        for (String mode : orEmpty(scenario.getAllowedAnalysisModes())) {
            if (!ANALYSIS_MODES.contains(mode)) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "allowedAnalysisModes 不在封闭枚举内: " + mode));
            }
            if (!modes.add(mode)) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "allowedAnalysisModes 重复: " + mode));
            }
        }
        Set<String> boundModes = new HashSet<>();
        for (ManifestPayload.SkillBinding skill : orEmpty(scenario.getSkills())) {
            String skillAt = at + ".skills." + skill.getSkillId();
            requireText(skill.getSkillId(), skillAt, "skillId 必填", violations);
            if (!refs.skillIds().contains(skill.getSkillId())) {
                violations.add(new ManifestStructureValidator.Violation(skillAt,
                        "只能绑定已发布 Skill: " + skill.getSkillId()));
            }
            requireEnum(skill.getAnalysisMode(), ANALYSIS_MODES, skillAt,
                    "analysisMode 非法: ", violations);
            requireText(skill.getParamsSchemaRef(), skillAt, "paramsSchemaRef 必填", violations);
            if (skill.getAnalysisMode() != null && !modes.contains(skill.getAnalysisMode())) {
                violations.add(new ManifestStructureValidator.Violation(skillAt,
                        "绑定的 analysisMode 未在 allowedAnalysisModes 中声明: " + skill.getAnalysisMode()));
            }
            boundModes.add(skill.getAnalysisMode());
        }
        if (!modes.isEmpty() && !boundModes.isEmpty() && !boundModes.containsAll(modes)) {
            violations.add(new ManifestStructureValidator.Violation(at,
                    "allowedAnalysisModes 存在未被任何 Skill 承担的模式: "
                            + difference(modes, boundModes)));
        }
    }

    private void validateHints(ManifestPayload.ScenarioMeta scenario, String at,
                               List<ManifestStructureValidator.Violation> violations) {
        List<String> hints = orEmpty(scenario.getEntryHints());
        if (hints.size() > MAX_ENTRY_HINTS) {
            violations.add(new ManifestStructureValidator.Violation(at,
                    "entryHints 不得超过 " + MAX_ENTRY_HINTS + " 条"));
        }
        Set<String> seen = new HashSet<>();
        for (String hint : hints) {
            if (hint == null || hint.isBlank()) {
                violations.add(new ManifestStructureValidator.Violation(at, "entryHints 不得包含空值"));
            } else if (!seen.add(hint)) {
                violations.add(new ManifestStructureValidator.Violation(at, "entryHints 重复: " + hint));
            }
        }
    }

    private void validateColdStart(ManifestPayload.ScenarioMeta scenario, PublishedRefs refs, String at,
                                   List<ManifestStructureValidator.Violation> violations) {
        for (String vqrId : orEmpty(scenario.getDefaultVqrIds())) {
            if (!refs.vqrIds().contains(vqrId)) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "defaultVqrIds 只能引用已发布 VQR: " + vqrId));
            }
        }
        for (String sample : orEmpty(scenario.getColdStartSamples())) {
            if (!refs.vqrIds().contains(sample) && !refs.goldenQueryIds().contains(sample)) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "coldStartSamples 必须是已发布 VQR / Golden Query 引用: " + sample));
            }
        }
    }

    private void validateVersionCompatibility(ManifestPayload.ScenarioMeta scenario, Index index, String at,
                                              List<ManifestStructureValidator.Violation> violations) {
        ManifestPayload.VersionCompatibility compatibility = scenario.getVersionCompatibility();
        if (compatibility == null) {
            return;
        }
        checkCompatibility(compatibility.getOntology(), index.ontologyVersion,
                at + ".versionCompatibility.ontology", violations);
        checkCompatibility(compatibility.getSemantic(), index.semanticVersion,
                at + ".versionCompatibility.semantic", violations);
    }

    private void checkCompatibility(String constraint, String current, String at,
                                    List<ManifestStructureValidator.Violation> violations) {
        if (constraint == null || constraint.isBlank()) {
            return;
        }
        if (constraint.startsWith("^")) {
            String base = constraint.substring(1);
            String[] parts = base.split("\\.");
            if (parts.length != 3 || !parts[0].matches("\\d+")) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "SemVer 约束格式非法（示例 ^1.2.3）: " + constraint));
                return;
            }
            String[] currentParts = current == null ? new String[0] : current.split("\\.");
            if (currentParts.length < 1 || !parts[0].equals(currentParts[0])) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "主版本不兼容: 约束 " + constraint + " / 当前 " + current));
            }
            return;
        }
        String prefix = constraint.endsWith("*")
                ? constraint.substring(0, constraint.length() - 1) : constraint;
        if (current == null || !(current.equals(prefix) || current.startsWith(prefix + ".")
                || (constraint.endsWith("*") && current.startsWith(prefix)))) {
            violations.add(new ManifestStructureValidator.Violation(at,
                    "版本前缀不兼容: 约束 " + constraint + " / 当前 " + current));
        }
    }

    private void validateInvalidation(ManifestPayload.ScenarioMeta scenario, String at,
                                      List<ManifestStructureValidator.Violation> violations) {
        ManifestPayload.Invalidation invalidation = scenario.getInvalidation();
        if (invalidation == null) {
            return;
        }
        requireEnum(invalidation.getOnScopeAssetDeprecation(), INVALIDATION_ACTIONS, at,
                "invalidation.onScopeAssetDeprecation 非法: ", violations);
        requireEnum(invalidation.getOnSkillDeprecation(), INVALIDATION_ACTIONS, at,
                "invalidation.onSkillDeprecation 非法: ", violations);
    }

    private static void requireText(String value, String at, String message,
                                    List<ManifestStructureValidator.Violation> violations) {
        if (value == null || value.isBlank()) {
            violations.add(new ManifestStructureValidator.Violation(at, message));
        }
    }

    private static void requireEnum(String value, Set<String> allowed, String at, String prefix,
                                    List<ManifestStructureValidator.Violation> violations) {
        if (value == null || !allowed.contains(value)) {
            violations.add(new ManifestStructureValidator.Violation(at, prefix + value));
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.removeAll(right);
        return result;
    }

    /** 清单内可被引用的资产索引，一次构建供全部 Scenario 复用。 */
    private static final class Index {
        private final Set<String> conceptIds = new HashSet<>();
        private final java.util.Map<String, OntologySnapshot.Relation> relationById = new java.util.HashMap<>();
        private final Set<String> metricIds = new HashSet<>();
        private final Set<String> dimensionIds = new HashSet<>();
        private final Set<String> propertyIds = new HashSet<>();
        private final String ontologyVersion;
        private final String semanticVersion;

        private Index(ManifestPayload payload) {
            OntologySnapshot ontology = payload.getOntology();
            if (ontology != null) {
                ontologyVersion = ontology.getOntologyVersion();
                orEmpty(ontology.getConcepts()).forEach(c -> conceptIds.add(c.getId()));
                orEmpty(ontology.getRelations()).forEach(r -> relationById.put(r.getId(), r));
                orEmpty(ontology.getProperties()).forEach(p -> propertyIds.add(p.getId()));
            } else {
                ontologyVersion = null;
            }
            SemanticManifest semantic = payload.getSemantic();
            if (semantic != null) {
                semanticVersion = semantic.getSemanticVersion();
                orEmpty(semantic.getMetrics()).forEach(m -> metricIds.add(m.getId()));
                orEmpty(semantic.getDimensions()).forEach(d -> dimensionIds.add(d.getId()));
            } else {
                semanticVersion = null;
            }
        }
    }
}
