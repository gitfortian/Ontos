package com.ontos.module.query.core.executor;

import com.ontos.module.query.core.compiler.CompiledQuery;
import lombok.Data;

/**
 * 执行计划与成本估算（M2/W4/T4.1）。
 *
 * <p>由 {@code EXPLAIN (FORMAT JSON)} 在同一身份上下文、同一只读连接中取得；
 * status 是服务端判定结果（OK / BLOCKED），不接受客户端提交。
 */
@Data
public class QueryPlan {

    public static final String OK = "OK";
    public static final String BLOCKED = "BLOCKED";

    /** 原始 EXPLAIN JSON，供 dry-plan 展示与审计回放到 T4.5 产物表。 */
    private String planJson;
    /** 顶层 Total Cost；成本为保守估计，硬防线仍是超时/行数。 */
    private double estimatedCost;
    /** 顶层 Plan Rows。 */
    private long estimatedRows;
    private String status = OK;
    private String blockReason;
    /** dry-run 是否已完成真实数据库侧的零行校验。 */
    private boolean verified;
    /** 本次计划对应的唯一编译产物（由 service 回填），dry-plan 与正式执行共用同一份。 */
    private CompiledQuery compiled;

}
