package com.ontos.module.query.service;

import com.ontos.module.query.core.compiler.CompiledQuery;
import com.ontos.module.query.core.dsl.QueryDsl;
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

}
