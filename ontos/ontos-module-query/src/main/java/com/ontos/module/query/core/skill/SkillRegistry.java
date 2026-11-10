package com.ontos.module.query.core.skill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import org.springframework.stereotype.Component;

/**
 * 能力注册中心（M3/W3 建立，M6/W3/T3.2 升级为**清单驱动注册制**）。
 *
 * <p><b>注册制语义（v1.4 §5.8 / M6 §3.3）</b>：清单 skills 段是资产源（published 才可执行），
 * implementation_ref 按 {@code builtin.<MODE>.<impl>} 前缀解析到内置 handler——
 * 清单注册了但无内置映射 = 拒绝执行（fail-closed，不假装支持）。
 *
 * <p>兼容纪律：{@link #list()} 仍只列**有内置 handler** 的能力（M3 对外契约不回退——
 * entity_lookup 在列且可执行）；清单段元数据经 {@link #manifestSkill(String)} 与
 * {@link #carriersFor(String, String)} 暴露给编译期校验与编排（T3.3 近似降级消费）。
 */
@Component
public class SkillRegistry {

    /** implementation_ref 去版本前缀（builtin.<MODE>.<impl>）→ handler。 */
    private final Map<String, SkillHandler> handlersByBuiltinRef;
    /** 全量 descriptor skillId 视图：legacy 兼容与 list() 去重的统一底表。 */
    private final Map<String, SkillHandler> handlersBySkillId;
    /** 未纳入注册制（无 builtinRefs 声明）的直挂 handler。 */
    private final Map<String, SkillHandler> legacyHandlersBySkillId;

    private final ManifestService manifestService;

    public SkillRegistry(List<SkillHandler> handlerList, ManifestService manifestService) {
        this.manifestService = manifestService;
        Map<String, SkillHandler> byRef = new HashMap<>();
        Map<String, SkillHandler> legacy = new HashMap<>();
        for (SkillHandler handler : handlerList) {
            List<String> refs = handler.builtinRefs();
            if (refs == null || refs.isEmpty()) {
                legacy.put(handler.descriptor().skillId(), handler);
                continue;
            }
            for (String builtinRef : refs) {
                byRef.put(builtinRef, handler);
            }
        }
        this.handlersByBuiltinRef = Map.copyOf(byRef);
        // 全量 descriptor skillId 视图：legacy 兼容与 list() 去重的统一底表
        this.handlersBySkillId = handlerList.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        handler -> handler.descriptor().skillId(), Function.identity(),
                        (first, second) -> first));
        this.legacyHandlersBySkillId = Map.copyOf(legacy);
    }

    /** 能力清单（按 skillId 排序，保证对外列表稳定）——只含**可执行**能力，M3 契约不回退。 */
    public List<SkillDescriptor> list() {
        // 一个 handler 可声明多个命名空间（单实体 + 批量共用构造器）——按 skillId 去重
        Map<String, SkillDescriptor> distinct = new HashMap<>();
        handlersByBuiltinRef.values().stream().map(SkillHandler::descriptor)
                .forEach(descriptor -> distinct.putIfAbsent(descriptor.skillId(), descriptor));
        legacyHandlersBySkillId.values().stream().map(SkillHandler::descriptor)
                .forEach(descriptor -> distinct.putIfAbsent(descriptor.skillId(), descriptor));
        return distinct.values().stream()
                .sorted(Comparator.comparing(SkillDescriptor::skillId))
                .toList();
    }

    /**
     * 清单驱动的解析（T3.2 注册制）：skillId 必须在当前生效清单 skills 段且 published，
     * implementation_ref 必须解析到内置 handler。未发布 / 不存在同形返回 empty（不泄露存在性）；
     * 注册但无映射也返回 empty（调用方 fail-closed）。
     */
    public Optional<SkillHandler> byId(String skillId) {
        return manifestSkill(skillId).flatMap(meta -> resolve(meta.getImplementationRef()));
    }

    /** 清单 skills 段的 published 元数据（编译期校验四条的数据源）。 */
    public Optional<ManifestPayload.SkillMeta> manifestSkill(String skillId) {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getSkills() == null || skillId == null) {
            return Optional.empty();
        }
        return manifest.getSkills().stream()
                .filter(skill -> skillId.equals(skill.getSkillId()))
                .filter(skill -> "published".equals(skill.getStatus()))
                .findFirst();
    }

    /**
     * 指定模式的承担者（T3.3 近似降级与编排模式过滤的数据源）：
     * 清单 published + analysis_mode 匹配 + required_capabilities 被受控工具注册表满足 +
     * allowed_scenario_ids 语义（空 = 全局；非空 = 必须包含当前场景）。
     */
    public List<ManifestPayload.SkillMeta> carriersFor(String analysisMode, String currentScenarioId) {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getSkills() == null || analysisMode == null) {
            return List.of();
        }
        Set<String> satisfied = ControlledCapabilities.satisfied(manifest);
        List<ManifestPayload.SkillMeta> carriers = new ArrayList<>();
        for (ManifestPayload.SkillMeta skill : manifest.getSkills()) {
            if (!analysisMode.equalsIgnoreCase(skill.getAnalysisMode())
                    || !"published".equals(skill.getStatus())) {
                continue;
            }
            if (!satisfied.containsAll(skill.getRequiredCapabilities())) {
                continue;
            }
            List<String> allowed = skill.getAllowedScenarioIds();
            if (allowed != null && !allowed.isEmpty()
                    && (currentScenarioId == null || !allowed.contains(currentScenarioId))) {
                continue;
            }
            carriers.add(skill);
        }
        return List.copyOf(carriers);
    }

    /**
     * 当前清单是否处于注册制形态（T32-6 修复）：清单**有** skills 段 = 严格注册制（段里没有即拒绝）；
     * 清单**没有** skills 段（v13 及更早的存量清单）= M3 直挂兼容态——byId/manifestSkill 回退
     * legacy 表，保证「注册制升级不破坏存量行为」的验收口径。
     */
    public boolean isRegistrationMode(PublishedManifest manifest) {
        return manifest != null && manifest.getSkills() != null && !manifest.getSkills().isEmpty();
    }

    /** M3 直挂兼容查询（仅注册制判定为 false 时可走）——查**全量** skillId 视图。 */
    public Optional<SkillHandler> legacyById(String skillId) {
        return Optional.ofNullable(handlersBySkillId.get(skillId));
    }

    private Optional<SkillHandler> resolve(String implementationRef) {
        if (implementationRef == null || implementationRef.isBlank()) {
            return Optional.empty();
        }
        // builtin.<MODE>.<impl>@<version> → 去版本前缀
        int at = implementationRef.indexOf('@');
        String namespace = at > 0 ? implementationRef.substring(0, at) : implementationRef;
        return Optional.ofNullable(handlersByBuiltinRef.get(namespace));
    }
}
