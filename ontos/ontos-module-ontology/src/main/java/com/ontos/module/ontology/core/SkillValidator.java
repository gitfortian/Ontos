package com.ontos.module.ontology.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Skill 资产校验（v1.4 §5.8，M6/W3/T3.1）：发布预检与资产管理面共用同一套判定
 * （对齐 {@link ScenarioValidator} 模式——ManifestStructureValidator 委派本类，不另立规则）。
 *
 * <p>校验面：生命周期必填（owner / SemVer version / status / analysis_mode 闭枚举）、
 * implementation_ref 命名空间（{@code builtin.<mode>.<impl>@<semver>}，且 mode 段必须与
 * analysis_mode 一致——Scenario 作者不可自定义命名空间）、allowed_scenario_ids 引用已发布
 * 场景（空 = 全局）、approximation_of 跨模式引用（近似承担者与被近似对象不得同模式）、
 * max_batch_size 下界、published 必有审核人记录（T3.1 特有）。
 */
public class SkillValidator {

    /** 大写枚举（同 skill_definition 列约束）；词表与 AnalysisMode 一致，DISCOVERY 不注册 Skill。 */
    private static final Set<String> ANALYSIS_MODES = Set.of(
            "QUERY", "LOOKUP", "LOOKUP_BATCH", "YOY_MOM", "FUNNEL", "RETENTION", "ATTRIBUTION");
    private static final Pattern SEMVER = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Pattern IMPLEMENTATION_REF =
            Pattern.compile("builtin\\.([A-Z_]+)\\.[A-Za-z0-9_]+@\\d+\\.\\d+\\.\\d+");
    private static final int MAX_BATCH_LIMIT = 10_000;

    public List<ManifestStructureValidator.Violation> validate(ManifestPayload payload) {
        List<ManifestStructureValidator.Violation> violations = new ArrayList<>();
        if (payload == null || payload.getSkills() == null) {
            return violations;
        }
        Set<String> scenarioIds = publishedScenarioIds(payload);
        Set<String> skillIds = new HashSet<>();
        for (ManifestPayload.SkillMeta skill : payload.getSkills()) {
            String at = "skill." + skill.getSkillId();
            if (skill.getSkillId() == null || skill.getSkillId().isBlank()) {
                violations.add(new ManifestStructureValidator.Violation("skill", "Skill ID 不能为空"));
                continue;
            }
            if (!skillIds.add(skill.getSkillId())) {
                violations.add(new ManifestStructureValidator.Violation(at, "Skill ID 重复"));
            }
        }
        for (ManifestPayload.SkillMeta skill : payload.getSkills()) {
            violations.addAll(validateAgainst(skill, scenarioIds, skillIds, payload.getSkills(),
                    "skill." + skill.getSkillId()));
        }
        return violations;
    }

    /** 单 Skill 校验（资产管理面发布时对候选 Skill 与目标清单复用同一套规则）。 */
    public List<ManifestStructureValidator.Violation> validateOne(ManifestPayload.SkillMeta skill,
                                                                  ManifestPayload payload) {
        if (skill == null) {
            return List.of(new ManifestStructureValidator.Violation("skill", "Skill 不能为空"));
        }
        if (skill.getSkillId() == null || skill.getSkillId().isBlank()) {
            return List.of(new ManifestStructureValidator.Violation("skill", "Skill ID 不能为空"));
        }
        Set<String> scenarioIds = publishedScenarioIds(payload);
        Set<String> skillIds = new HashSet<>();
        List<ManifestPayload.SkillMeta> allSkills = payload.getSkills() == null
                ? List.of() : payload.getSkills();
        allSkills.stream().map(ManifestPayload.SkillMeta::getSkillId)
                .filter(id -> id != null).forEach(skillIds::add);
        return validateAgainst(skill, scenarioIds, skillIds, allSkills, "skill." + skill.getSkillId());
    }

    private List<ManifestStructureValidator.Violation> validateAgainst(ManifestPayload.SkillMeta skill,
                                                                       Set<String> scenarioIds,
                                                                       Set<String> knownSkillIds,
                                                                       List<ManifestPayload.SkillMeta> allSkills,
                                                                       String at) {
        List<ManifestStructureValidator.Violation> violations = new ArrayList<>();
        if (skill.getName() == null || skill.getName().isBlank()) {
            violations.add(new ManifestStructureValidator.Violation(at, "name 必填"));
        }
        if (skill.getOwner() == null || skill.getOwner().isBlank()) {
            violations.add(new ManifestStructureValidator.Violation(at, "owner 必填"));
        }
        if (skill.getVersion() == null || !SEMVER.matcher(skill.getVersion()).matches()) {
            violations.add(new ManifestStructureValidator.Violation(at, "version 必须 SemVer: "
                    + skill.getVersion()));
        }
        String status = skill.getStatus();
        if (!"draft".equals(status) && !"reviewing".equals(status)
                && !"published".equals(status) && !"retired".equals(status)) {
            violations.add(new ManifestStructureValidator.Violation(at, "status 非法: " + status));
        }
        // published 必有审核人记录（T3.1 特有：人工审核留痕）
        if ("published".equals(status)
                && (skill.getReviewer() == null || skill.getReviewer().isBlank())) {
            violations.add(new ManifestStructureValidator.Violation(at, "published 必须有审核人记录 (reviewer)"));
        }
        // analysis_mode 闭枚举（DISCOVERY 不注册 Skill——M6 规格 §3.3 首发注册模式表）
        String mode = skill.getAnalysisMode();
        if (!ANALYSIS_MODES.contains(mode)) {
            violations.add(new ManifestStructureValidator.Violation(at,
                    "analysisMode 非法或不支持 Skill 化: " + mode));
            mode = null;
        }
        // implementation_ref 命名空间：builtin.<mode>.<impl>@<semver>，mode 段与 analysisMode 一致
        String ref = skill.getImplementationRef();
        if (ref == null || ref.isBlank()) {
            violations.add(new ManifestStructureValidator.Violation(at, "implementationRef 必填"));
        } else {
            var matcher = IMPLEMENTATION_REF.matcher(ref);
            if (!matcher.matches()) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "implementationRef 必须是 builtin.<mode>.<impl>@<semver>: " + ref));
            } else if (mode != null && !matcher.group(1).equals(mode)) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "implementationRef 命名空间 mode 段与 analysisMode 不一致: " + ref));
            }
        }
        // allowed_scenario_ids：引用已发布场景；空 = 全局
        for (String scenarioId : skill.getAllowedScenarioIds()) {
            if (!scenarioIds.contains(scenarioId)) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "allowedScenarioIds 引用未发布场景: " + scenarioId));
            }
        }
        // approximation_of：跨模式预声明近似（§3.3）——被引用 Skill 必须在清单且模式不同
        String approximation = skill.getApproximationOf();
        if (approximation != null && !approximation.isBlank()) {
            ManifestPayload.SkillMeta target = null;
            if (knownSkillIds.contains(approximation)) {
                for (ManifestPayload.SkillMeta candidate : allSkills) {
                    if (approximation.equals(candidate.getSkillId())) {
                        target = candidate;
                        break;
                    }
                }
            }
            if (target == null) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "approximationOf 引用的 Skill 不在清单: " + approximation));
            } else if (skill.getAnalysisMode() != null
                    && skill.getAnalysisMode().equalsIgnoreCase(target.getAnalysisMode())) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "approximationOf 必须跨模式引用（近似承担者与被近似对象同模式即自我近似）: "
                                + approximation));
            }
        }
        // required_capabilities：受控能力闭词表（M7/W4/T4.4，D-22）
        // 发布期只校验「非空 / 去重 / 词表内」；rule.evaluate 的运行时可满足性仍由
        // ControlledCapabilities 按当前清单/部署判定（纵深防御，不在发布期伪装运行时结论）。
        if (skill.getRequiredCapabilities() != null) {
            Set<String> seenCapabilities = new HashSet<>();
            for (String capability : skill.getRequiredCapabilities()) {
                if (capability == null || capability.isBlank()) {
                    violations.add(new ManifestStructureValidator.Violation(at,
                            "requiredCapabilities 不能包含空值"));
                    continue;
                }
                if (!seenCapabilities.add(capability)) {
                    violations.add(new ManifestStructureValidator.Violation(at,
                            "requiredCapabilities 重复: " + capability));
                }
                if (!SkillCapabilities.ALL.contains(capability)) {
                    violations.add(new ManifestStructureValidator.Violation(at,
                            "requiredCapabilities 未在受控词表登记: " + capability));
                }
            }
        }
        // max_batch_size 下界（列约束同款语义，清单侧前置拦截）
        if (skill.getMaxBatchSize() != null
                && (skill.getMaxBatchSize() < 1 || skill.getMaxBatchSize() > MAX_BATCH_LIMIT)) {
            violations.add(new ManifestStructureValidator.Violation(at,
                    "maxBatchSize 越界（1–" + MAX_BATCH_LIMIT + "）: " + skill.getMaxBatchSize()));
        }
        return violations;
    }

    private static Set<String> publishedScenarioIds(ManifestPayload payload) {
        Set<String> ids = new HashSet<>();
        if (payload.getScenarios() != null) {
            payload.getScenarios().stream()
                    .filter(scenario -> "published".equals(scenario.getStatus()))
                    .map(ManifestPayload.ScenarioMeta::getId)
                    .forEach(ids::add);
        }
        return ids;
    }
}
