package com.ontos.module.agent.config;

import java.time.Duration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ask 编排配置（M5/W4，规格 §3.7 / v1.1 F-8）。
 */
@Data
@ConfigurationProperties(prefix = "ontos.ask")
public class AskProperties {

    /** run 执行线程池大小：HITL 轮询在 run 执行线程内进行（不占 REST 线程）。 */
    private int runExecutorPool = 4;

    /** POST /ask/runs 同步等待时长：超时返回 waiting 中间态，run 继续在线程池执行。 */
    private Duration runWaitTimeout = Duration.ofSeconds(30);

    /** 审批状态轮询间隔（R4/T4.2：1s 间隔 DB 轮询，多副本一致）。 */
    private Duration approvalPollInterval = Duration.ofSeconds(1);

    /** 审批等待上限（对齐 M4 审批 TTL 默认 PT15M）：超时 run 置 EXPIRED，审批单仍有效。 */
    private Duration approvalWaitLimit = Duration.ofMinutes(15);

}
