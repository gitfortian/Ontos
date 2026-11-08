package com.ontos.module.query.core;

import java.time.Duration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 查询资源约束。编译器与执行器共用同一组上限，避免两处阈值漂移。
 */
@Data
@ConfigurationProperties(prefix = "ontos.query")
public class QueryProperties {

    /** 单次结果返回上限，编译器与执行器双重截断。 */
    private int maxRows = 500;
    /** 单个子查询的语句超时。 */
    private Duration statementTimeout = Duration.ofSeconds(15);
    /** lookup_batch 单批上限（v1.4 8.2）。 */
    private int maxBatchSize = 50;
    /** 敏感资产在无维度分组时允许的最大行数；超过即按「批量导出形状」拒绝（M2 编译侧 guardrail）。 */
    private int sensitiveUngroupedLimit = 50;

}
