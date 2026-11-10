package com.ontos.module.query.core.skill;

import java.util.Map;

import com.ontos.module.query.core.dsl.QueryDsl;

/**
 * 能力处理器（M3/W3）：把「槽位 → 类型化 DSL」的组装收敛到唯一入口。
 *
 * <p>实现方只做槽位结构与类型的严格校验（未知槽位 / 缺必填 / 类型不符一律业务错误），
 * 资产存在性、授权、时间窗等交给下游 {@code QueryService.execute} 的统一闸门——
 * 能力层绝不复制校验逻辑，也不放宽它。
 */
public interface SkillHandler {

    SkillDescriptor descriptor();

    /**
     * 内置实现命名空间（M6/W3/T3.2）：{@code builtin.<MODE>.<impl>} 列表（不带版本——版本随清单演进，
     * handler 绑定到命名空间而非精确版本）。注册表按清单 skills 段的 implementation_ref
     * 去版本前缀解析到本映射；返回空 = 该 handler 未纳入注册制（M3 直挂兼容态）。
     */
    default java.util.List<String> builtinRefs() {
        return java.util.List.of();
    }

    /**
     * 槽位 → DSL。manifestId 由服务层注入（取当前生效清单），调用方不感知清单版本。
     */
    QueryDsl build(Map<String, Object> slots, String manifestId);
}
