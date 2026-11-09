package com.ontos.module.query.core.skill;

import java.util.List;

/**
 * 能力描述（M3/W3，roadmap M3 交付物 1）：能力注册的对外形态。
 *
 * <p>槽位是「调用方必须/可以提供的业务输入」，只允许资产 ID 与标量值，
 * 不允许物理表名、SQL 片段或任意表达式——能力层是 DSL 的前置封装，不是绕过校验的旁路。
 */
public record SkillDescriptor(String skillId, String name, String summary, List<SkillSlot> slots) {

    /** 一个能力槽位：required=false 表示可省略（由能力内部定档默认值）。 */
    public record SkillSlot(String name, boolean required, String description) {
    }
}
