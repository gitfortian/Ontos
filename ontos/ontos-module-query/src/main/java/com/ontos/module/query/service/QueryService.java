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

}
