package com.ontos.module.query.service;

import com.ontos.module.query.core.compiler.CompiledQuery;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.query.core.executor.DryRunOutcome;
import com.ontos.module.query.core.executor.QueryPlan;
import com.ontos.module.query.core.executor.QueryResult;

/**
 * 查询服务：校验 → 编译 → 执行 → 审计。所有入口共用同一条确定性链路。
 */
public interface QueryService {

    /**
     * dry-plan：只编译与审计，不访问 Query DS。
     */
    CompiledQuery compile(QueryDsl queryDsl);

    /**
     * 正式执行：编译后经只读连接执行，并写入审计。
     */
    QueryResult execute(QueryDsl queryDsl);

    /**
     * dry-plan：与 compile/execute 复用同一 CompiledQuery，额外做 EXPLAIN 成本估算；
     * 不执行业务查询、不返回业务行。超阈值时计划标 BLOCKED。
     */
    QueryPlan dryPlan(QueryDsl queryDsl);

    /**
     * dry-run：同一编译产物在更紧的超时与行数上限下受限执行，返回计划 + 结果 + 执行元数据；
     * 计划超阈值直接拒绝。调试通道，不推进业务状态机、不做幂等。
     */
    DryRunOutcome dryRun(QueryDsl queryDsl);

    /**
     * 按 request_id 回放最近一次编译产物（SQL + 绑定值 + Join 路径 + 计划）；
     * 只在本租户 + 本用户范围内可查，查不到按 ARTIFACT_NOT_FOUND 拒绝。
     */
    QueryPlan replayPlan(String requestId);

    /**
     * 能力调用（M3/W3）：按 skillId 组装 DSL 后走正式执行通道（校验/授权/审计/状态机/幂等全部不变）；
     * 另写一条 SKILL_EXECUTE 审计记录「这次查询由哪个能力发起」。
     */
    QueryResult executeSkill(String skillId, java.util.Map<String, Object> slots);

    /**
     * 近似降级执行（M6/W3/T3.3，ADR-0017 D3）：与 executeSkill 同一套编译期闸门，
     * 审计记 {@code MODE_APPROXIMATED} + 原请求 mode（用户同意后的降级执行必须显式留痕）。
     */
    QueryResult executeSkillApproximation(String skillId, java.util.Map<String, Object> slots,
                                          String originalMode);

    /**
     * 复合意图执行（M2 只支持无依赖子集）：逐子独立校验 → 编译 → 授权 → 执行 → 审计，
     * 子间不合并 SQL、不共享中间结果。任一子失败整单拒绝（fail-closed，不返回部分结果）；
     * 结果依赖类子意图（dependsOn 非空）直接拒绝转澄清，全链路归 M6。
     */
    CompositeOutcome executeComposite(QueryDsl queryDsl);

}
