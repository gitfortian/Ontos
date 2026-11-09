package com.ontos.module.query.core.skill;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * 能力注册中心（M3/W3，roadmap M3 交付物 1）。
 *
 * <p>落点按 M3 规格 §8 决策 1：先放 query 模块的功能包，M5 需要会话编排时再抽独立模块。
 * 处理器由 Spring 注入聚合——新能力只要实现 {@link SkillHandler} 就自动注册，
 * 注册中心不做任何按 ID 的硬编码分支。
 */
@Component
public class SkillRegistry {

    private final Map<String, SkillHandler> handlers;

    public SkillRegistry(List<SkillHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toUnmodifiableMap(
                        handler -> handler.descriptor().skillId(), Function.identity()));
    }

    /** 能力清单（按 skillId 排序，保证对外列表稳定）。 */
    public List<SkillDescriptor> list() {
        return handlers.values().stream()
                .map(SkillHandler::descriptor)
                .sorted(Comparator.comparing(SkillDescriptor::skillId))
                .toList();
    }

    public Optional<SkillHandler> byId(String skillId) {
        return Optional.ofNullable(handlers.get(skillId));
    }
}
