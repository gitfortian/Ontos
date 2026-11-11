package com.ontos.module.ontology.core;

import java.util.Set;

/**
 * Skill 受控能力闭词表（M7/W4/T4.4，D-22）。
 *
 * <p>词表放在 {@code ontos-module-ontology}：发布期校验（{@link SkillValidator}）与运行时
 * 能力判定（{@code ontos-module-query} 的 {@code ControlledCapabilities}）共用同一份定义，
 * 避免 ontology 反向依赖 query（ArchUnit 边界）。
 *
 * <p>语义：
 * <ul>
 *   <li>{@code semantic.query}：本体检索 + 类型化 DSL 编译——随部署恒可用；</li>
 *   <li>{@code rule.evaluate}：判定标准评估——要求 Rule 注册中心可用且清单 rules 段存在
 *       published 规则（运行时判定，发布期只校验词表合法）。</li>
 * </ul>
 * 未登记的能力一律非法（发布期拒绝；运行时按未满足处理）。
 */
public final class SkillCapabilities {

    public static final String SEMANTIC_QUERY = "semantic.query";
    public static final String RULE_EVALUATE = "rule.evaluate";

    /** 全部受控能力（发布期闭词表；新增能力只能追加）。 */
    public static final Set<String> ALL = Set.of(SEMANTIC_QUERY, RULE_EVALUATE);

    private SkillCapabilities() {
    }
}
