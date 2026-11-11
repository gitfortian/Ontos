package com.ontos.module.ontology.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VQR 依赖影响分析 / 自动失效（v1.4 §11.3，M6/W3/T3.4；传播口径 v0.2 F-7）。
 *
 * <p>发布流水线在清单升级时调用：对照当前生效清单（旧）与新清单（新），对新清单 vqrs 段
 * 每个 VQR 做依赖影响分析，命中任一失效条件 → 从**新清单 payload** 移除该 VQR（清单不可变、
 * 只增版本——失效经发布流水线生效）。两次发布之间的窗口期 drift 由运行时八步重校验兜底
 * （命中 VQR 组装 DSL 后走既有 execute 全链，引用失效资产自然被拒）。
 *
 * <p>失效条件（§11.3，本类实现为可判定的子集，全部 fail-closed）：
 * <ul>
 *   <li>依赖资产废弃：模板引用的资产在新清单中消失；</li>
 *   <li>依赖资产不兼容变更：新旧清单中同一资产的定义 JSON 不一致（语义/规则/技能定义变化
 *       ——保守判定，任何变化都视为不兼容，宁误杀不放过）；</li>
 *   <li>依赖 Rule / Skill 失效：新清单中状态不再是 published；</li>
 *   <li>依赖 Skill 不兼容变更：主版本升级（SemVer major 变化）或 analysis_mode /
 *       paramsSchemaRef / requiredCapabilities / allowedScenarioIds / approximationOf 变化；</li>
 *   <li>引用场景的模式收窄：引用该 VQR 的场景 allowed_analysis_modes 不再包含模板 mode；</li>
 *   <li>声明式失效条件：VQR 自身声明 {assetId, trigger}，按同款语义判定。</li>
 * </ul>
 *
 * <p>判定「不静默」：失效不阻断发布（阻断会让一次无关升级被死 VQR 卡住），但结果必须以
 * 失效报告形式可见（调用方将报告带回发布响应）。
 */
public class VqrInvalidationAnalyzer {

    private static final Pattern SEMVER_MAJOR = Pattern.compile("^(\\d+)\\.\\d+\\.\\d+$");

    /** 单条失效：VQR + 命中原因（报告与审计用）。 */
    public record Invalidation(String vqrId, String reason) {
    }

    /**
     * 对新清单 payload 中的每个 VQR 做依赖影响分析。
     *
     * @param oldPayload 当前生效清单 payload（首次发布为 null——只做新清单自洽检查）
     * @param newPayload 待发布清单 payload
     * @return 命中失效条件的 VQR 及原因；调用方负责将其从 newPayload 移除
     */
    public List<Invalidation> analyze(ManifestPayload oldPayload, ManifestPayload newPayload) {
        if (newPayload == null || newPayload.getVqrs() == null || newPayload.getVqrs().isEmpty()) {
            return List.of();
        }
        Map<String, ManifestPayload.Rule> oldRules = rulesById(oldPayload);
        Map<String, ManifestPayload.Rule> newRules = rulesById(newPayload);
        Map<String, ManifestPayload.SkillMeta> oldSkills = skillsById(oldPayload);
        Map<String, ManifestPayload.SkillMeta> newSkills = skillsById(newPayload);
        AssetPresence oldAssets = AssetPresence.of(oldPayload);
        AssetPresence newAssets = AssetPresence.of(newPayload);
        Map<String, String> oldDefinitions = definitionsOf(oldPayload);
        Map<String, String> newDefinitions = definitionsOf(newPayload);

        List<Invalidation> invalidations = new ArrayList<>();
        for (ManifestPayload.VqrMeta vqr : newPayload.getVqrs()) {
            String at = vqr.getVqrId() == null ? "vqr" : vqr.getVqrId();
            Set<String> dependencies = VqrValidator.dependenciesOf(vqr.getDslTemplate());

            // ① 依赖资产废弃（新清单中消失）
            for (String assetId : dependencies) {
                if (!newAssets.present(assetId)) {
                    invalidations.add(new Invalidation(at, "依赖资产已废弃: " + assetId));
                }
            }
            // ② 依赖资产定义不兼容变更（新旧清单定义 JSON 不一致——保守判定）
            for (String assetId : dependencies) {
                String oldDef = oldDefinitions.get(assetId);
                String newDef = newDefinitions.get(assetId);
                if (oldDef != null && newDef != null && !oldDef.equals(newDef)) {
                    invalidations.add(new Invalidation(at, "依赖资产定义发生不兼容变更: " + assetId));
                }
            }
            // ③ 依赖 Rule / Skill 失效（新清单中不再是 published）
            for (String assetId : dependencies) {
                ManifestPayload.Rule rule = newRules.get(assetId);
                if (rule != null && !"published".equals(rule.getStatus())) {
                    invalidations.add(new Invalidation(at, "依赖 Rule 不再 published: " + assetId));
                }
                ManifestPayload.SkillMeta skill = newSkills.get(assetId);
                if (skill != null && !"published".equals(skill.getStatus())) {
                    invalidations.add(new Invalidation(at, "依赖 Skill 不再 published: " + assetId));
                }
            }
            // ④ 依赖 Skill 不兼容变更（主版本升级 / 结构字段变化）
            for (String assetId : dependencies) {
                ManifestPayload.SkillMeta oldSkill = oldSkills.get(assetId);
                ManifestPayload.SkillMeta newSkill = newSkills.get(assetId);
                if (oldSkill != null && newSkill != null
                        && skillIncompatible(oldSkill, newSkill)) {
                    invalidations.add(new Invalidation(at, "依赖 Skill 发生不兼容变更: " + assetId));
                }
            }
            // ⑤ 引用场景的模式收窄（allowed_analysis_modes 不再包含模板 mode）
            String templateMode = templateMode(vqr);
            if (templateMode != null && newPayload.getScenarios() != null) {
                for (ManifestPayload.ScenarioMeta scenario : newPayload.getScenarios()) {
                    if (!scenarioReferences(scenario, at)) {
                        continue;
                    }
                    List<String> modes = scenario.getAllowedAnalysisModes();
                    if (modes != null && !modes.isEmpty() && !modes.contains(templateMode)) {
                        invalidations.add(new Invalidation(at, "引用场景收窄了 allowed_analysis_modes: "
                                + scenario.getId() + " 不含 " + templateMode));
                    }
                }
            }
            // ⑥ 声明式失效条件（{assetId, trigger}，语义与 ①–④ 同款）
            if (vqr.getInvalidationConditions() != null) {
                for (Map<String, Object> condition : vqr.getInvalidationConditions()) {
                    Object assetId = condition.get("assetId");
                    Object trigger = condition.get("trigger");
                    if (!(assetId instanceof String id) || !(trigger instanceof String t)) {
                        continue;
                    }
                    switch (t) {
                        case "deprecated" -> {
                            if (!newAssets.present(id)) {
                                invalidations.add(new Invalidation(at,
                                        "声明失效条件命中（deprecated）: " + id));
                            } else if (retiredOrUnpublished(id, newRules, newSkills)) {
                                invalidations.add(new Invalidation(at,
                                        "声明失效条件命中（deprecated，资产不再 published）: " + id));
                            }
                        }
                        case "incompatible_change" -> {
                            String oldDef = oldDefinitions.get(id);
                            String newDef = newDefinitions.get(id);
                            if (oldDef != null && newDef != null && !oldDef.equals(newDef)) {
                                invalidations.add(new Invalidation(at,
                                        "声明失效条件命中（incompatible_change）: " + id));
                            }
                            ManifestPayload.SkillMeta oldSkill = oldSkills.get(id);
                            ManifestPayload.SkillMeta newSkill = newSkills.get(id);
                            if (oldSkill != null && newSkill != null
                                    && skillIncompatible(oldSkill, newSkill)) {
                                invalidations.add(new Invalidation(at,
                                        "声明失效条件命中（incompatible_change，Skill）: " + id));
                            }
                        }
                        default -> {
                            // 未知 trigger 由 VqrValidator 拦截，分析器忽略
                        }
                    }
                }
            }
        }
        // 同一 VQR 多条命中合并为一条记录（原因拼接，保序去重）
        Map<String, List<String>> merged = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (Invalidation invalidation : invalidations) {
            merged.computeIfAbsent(invalidation.vqrId(), key -> new ArrayList<>())
                    .add(invalidation.reason());
            if (!order.contains(invalidation.vqrId())) {
                order.add(invalidation.vqrId());
            }
        }
        List<Invalidation> result = new ArrayList<>();
        order.forEach(vqrId -> result.add(new Invalidation(vqrId,
                String.join("; ", merged.get(vqrId)))));
        return List.copyOf(result);
    }

    /**
     * 失效生效（T34-8 修复）：从新清单 payload 移除失效 VQR，并**级联清理**场景的
     * defaultVqrIds / coldStartSamples 引用——失效 VQR 留在场景引用里会把后续发布
     * 卡死在「只能引用已发布 VQR」（悬空引用 = 死引用，必须与失效同批清理）。
     *
     * @return 清理的场景引用条数（审计/报告用）
     */
    public int apply(ManifestPayload payload, List<Invalidation> invalidations) {
        if (payload == null || invalidations == null || invalidations.isEmpty()) {
            return 0;
        }
        Set<String> dead = new LinkedHashSet<>();
        invalidations.forEach(invalidation -> dead.add(invalidation.vqrId()));
        if (payload.getVqrs() != null && !payload.getVqrs().isEmpty()) {
            payload.setVqrs(payload.getVqrs().stream()
                    .filter(vqr -> !dead.contains(vqr.getVqrId())).toList());
        }
        int removed = 0;
        if (payload.getScenarios() != null) {
            for (ManifestPayload.ScenarioMeta scenario : payload.getScenarios()) {
                if (scenario.getDefaultVqrIds() != null
                        && scenario.getDefaultVqrIds().stream().anyMatch(dead::contains)) {
                    int before = scenario.getDefaultVqrIds().size();
                    scenario.setDefaultVqrIds(scenario.getDefaultVqrIds().stream()
                            .filter(id -> !dead.contains(id)).toList());
                    // M6 全量深度审核 D-6：计「实际移除条数」，不是清理前列表长度
                    removed += before - scenario.getDefaultVqrIds().size();
                }
                if (scenario.getColdStartSamples() != null
                        && scenario.getColdStartSamples().stream().anyMatch(dead::contains)) {
                    int before = scenario.getColdStartSamples().size();
                    scenario.setColdStartSamples(scenario.getColdStartSamples().stream()
                            .filter(id -> !dead.contains(id)).toList());
                    removed += before - scenario.getColdStartSamples().size();
                }
            }
        }
        return removed;
    }

    private static boolean retiredOrUnpublished(String assetId,
                                                Map<String, ManifestPayload.Rule> rules,
                                                Map<String, ManifestPayload.SkillMeta> skills) {
        ManifestPayload.Rule rule = rules.get(assetId);
        if (rule != null) {
            return !"published".equals(rule.getStatus());
        }
        ManifestPayload.SkillMeta skill = skills.get(assetId);
        return skill != null && !"published".equals(skill.getStatus());
    }

    /** Skill 不兼容变更：主版本升级或关键结构字段变化。 */
    static boolean skillIncompatible(ManifestPayload.SkillMeta oldSkill,
                                     ManifestPayload.SkillMeta newSkill) {
        if (majorOf(oldSkill.getVersion()) != majorOf(newSkill.getVersion())) {
            return true;
        }
        return !safeEquals(oldSkill.getAnalysisMode(), newSkill.getAnalysisMode())
                || !safeEquals(oldSkill.getParamsSchemaRef(), newSkill.getParamsSchemaRef())
                || !safeEquals(oldSkill.getRequiredCapabilities(), newSkill.getRequiredCapabilities())
                || !safeEquals(oldSkill.getAllowedScenarioIds(), newSkill.getAllowedScenarioIds())
                || !safeEquals(oldSkill.getApproximationOf(), newSkill.getApproximationOf());
    }

    private static int majorOf(String version) {
        if (version == null) {
            return -1;
        }
        Matcher matcher = SEMVER_MAJOR.matcher(version);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static boolean safeEquals(Object left, Object right) {
        return String.valueOf(left).equals(String.valueOf(right));
    }

    /** 模板声明的分析模式（dslTemplate.mode；缺失返回 null——无模式语义不参与场景收窄判定）。 */
    static String templateMode(ManifestPayload.VqrMeta vqr) {
        Object mode = vqr.getDslTemplate() == null ? null : vqr.getDslTemplate().get("mode");
        return mode instanceof String value && !value.isBlank() ? value.toLowerCase() : null;
    }

    /** 场景是否引用该 VQR（defaultVqrIds / coldStartSamples 任一）。 */
    static boolean scenarioReferences(ManifestPayload.ScenarioMeta scenario, String vqrId) {
        return (scenario.getDefaultVqrIds() != null && scenario.getDefaultVqrIds().contains(vqrId))
                || (scenario.getColdStartSamples() != null
                && scenario.getColdStartSamples().contains(vqrId));
    }

    private static Map<String, ManifestPayload.Rule> rulesById(ManifestPayload payload) {
        Map<String, ManifestPayload.Rule> map = new HashMap<>();
        if (payload != null && payload.getRules() != null) {
            payload.getRules().forEach(rule -> map.put(rule.getId(), rule));
        }
        return map;
    }

    private static Map<String, ManifestPayload.SkillMeta> skillsById(ManifestPayload payload) {
        Map<String, ManifestPayload.SkillMeta> map = new HashMap<>();
        if (payload != null && payload.getSkills() != null) {
            payload.getSkills().forEach(skill -> map.put(skill.getSkillId(), skill));
        }
        return map;
    }

    /** 资产 → 规范化定义 JSON（新旧对比的口径；无该资产的键缺省）。 */
    private static Map<String, String> definitionsOf(ManifestPayload payload) {
        Map<String, String> map = new HashMap<>();
        if (payload == null) {
            return map;
        }
        if (payload.getSemantic() != null) {
            safeForEach(payload.getSemantic().getMetrics(), metric ->
                    map.put(metric.getId(), Json.canonical(metric)));
            safeForEach(payload.getSemantic().getDimensions(), dimension ->
                    map.put(dimension.getId(), Json.canonical(dimension)));
        }
        if (payload.getOntology() != null) {
            safeForEach(payload.getOntology().getProperties(), property ->
                    map.put(property.getId(), Json.canonical(property)));
        }
        // M6 全量深度审核 D-5：失效分析在结构校验之前执行，显式 null 段不得 NPE（500）
        safeForEach(payload.getRules(), rule -> map.put(rule.getId(), Json.canonical(rule)));
        safeForEach(payload.getSkills(), skill -> map.put(skill.getSkillId(), Json.canonical(skill)));
        return map;
    }

    private static <T> void safeForEach(java.util.Collection<T> items, java.util.function.Consumer<T> action) {
        if (items != null) {
            items.forEach(action);
        }
    }

    /** 新旧清单中资产在场的并集判定源。 */
    private record AssetPresence(Set<String> assetIds) {
        static AssetPresence of(ManifestPayload payload) {
            Set<String> ids = new LinkedHashSet<>();
            if (payload != null) {
                if (payload.getSemantic() != null) {
                    safeForEach(payload.getSemantic().getMetrics(), m -> add(ids, m.getId()));
                    safeForEach(payload.getSemantic().getDimensions(), d -> add(ids, d.getId()));
                }
                if (payload.getOntology() != null) {
                    safeForEach(payload.getOntology().getConcepts(), c -> add(ids, c.getId()));
                    safeForEach(payload.getOntology().getProperties(), p -> add(ids, p.getId()));
                }
                safeForEach(payload.getRules(), rule -> add(ids, rule.getId()));
                safeForEach(payload.getSkills(), skill -> add(ids, skill.getSkillId()));
            }
            return new AssetPresence(Set.copyOf(ids));
        }

        private static void add(Set<String> ids, String id) {
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }

        boolean present(String assetId) {
            return assetIds.contains(assetId);
        }
    }

    /** 定义规范化的最小工具（Jackson 序列化；键序由 Jackson Map 序列化天然稳定）。 */
    private static final class Json {

        static String canonical(Object value) {
            try {
                return com.ontos.framework.common.util.json.JsonUtils.toJsonString(value);
            } catch (RuntimeException ex) {
                // 序列化失败 = 定义不可比——保守视为「已变更」由上层判定，
                // 但这里返回哨兵避免误判「未变更」
                return "<unserializable:" + ex.getClass().getSimpleName() + ">";
            }
        }
    }
}
