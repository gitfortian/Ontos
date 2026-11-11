package com.ontos.module.query.core.skill;

import java.util.Set;

import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;

/**
 * 受控工具注册表（v1.4 §5.8「required_capabilities 必须被当前部署的受控工具注册表全部满足」，
 * M6/W3/T3.2 编译期校验第 1 条的数据源）。
 *
 * <p>内置能力词表：
 * <ul>
 *   <li>{@code semantic.query}：本体检索 + 类型化 DSL 编译——随部署恒可用；</li>
 *   <li>{@code rule.evaluate}：判定标准评估——要求 Rule 注册中心可用**且**清单 rules 段
 *       存在 published 规则（M4/W2 交付的 rule 运行时）；</li>
 * </ul>
 * 未登记的能力一律不满足（fail-closed：清单声明了未知能力 = 拒绝路由该 Skill）。
 */
public final class ControlledCapabilities {

    // M7/W4/T4.4（D-22）：词表唯一来源下沉到 ontology（SkillCapabilities），
    // 发布期 SkillValidator 与运行时本类共用；此处保留常量别名避免调用方改动。
    public static final String SEMANTIC_QUERY = com.ontos.module.ontology.core.SkillCapabilities.SEMANTIC_QUERY;
    public static final String RULE_EVALUATE = com.ontos.module.ontology.core.SkillCapabilities.RULE_EVALUATE;

    private static final Set<String> ALWAYS_AVAILABLE = Set.of(SEMANTIC_QUERY);

    private ControlledCapabilities() {
    }

    /** 当前生效清单下被满足的能力集合（每次调用按当前清单重算——Rule 注册状态随清单演进）。 */
    public static Set<String> satisfied(PublishedManifest manifest) {
        if (manifest == null) {
            return Set.of();
        }
        java.util.Set<String> satisfied = new java.util.HashSet<>(ALWAYS_AVAILABLE);
        boolean ruleCenterReady = manifest.getRules() != null && manifest.getRules().stream()
                .anyMatch(rule -> "published".equals(rule.getStatus()));
        if (ruleCenterReady) {
            satisfied.add(RULE_EVALUATE);
        }
        return Set.copyOf(satisfied);
    }

    /** 指定 Skill 的未满足能力（空 = 可路由）；未知能力一律视为未满足。 */
    public static Set<String> unsatisfiedOf(ManifestPayload.SkillMeta skill, PublishedManifest manifest) {
        Set<String> satisfied = satisfied(manifest);
        java.util.Set<String> missing = new java.util.HashSet<>();
        if (skill.getRequiredCapabilities() != null) {
            for (String capability : skill.getRequiredCapabilities()) {
                if (capability != null && !capability.isBlank() && !satisfied.contains(capability)) {
                    missing.add(capability);
                }
            }
        }
        return Set.copyOf(missing);
    }
}
