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

    /**
     * run 执行线程池大小：HITL 轮询在 run 执行线程内进行（不占 REST 线程）。
     * T5.3 扩容（M5 验收报告 §5 移交项）：审批等待为 DB 轮询 IO 密集型，固定池在多审批
     * 并行等待时占满阻塞新 run——默认 4 → 16（单实例多审批并行场景）。
     */
    private int runExecutorPool = 16;

    /**
     * ask_run_event 保留天数（G-5 保留策略，与 audit_log 同规的默认口径）：
     * {@code 0 = 永久保留}（只追加、不清理——审计/追责语义，默认）；
     * {@code > 0} 启用按 {@link #eventRetentionSweepInterval} 周期清理更早的事件
     * （运维显式开启，事件表增长风险台账 §8 的兜底闸门）。
     */
    private int eventRetentionDays = 0;

    /** 事件保留清理扫描周期（仅 eventRetentionDays > 0 时生效）。 */
    private Duration eventRetentionSweepInterval = Duration.ofHours(6);

    /** POST /ask/runs 同步等待时长：超时返回 waiting 中间态，run 继续在线程池执行。 */
    private Duration runWaitTimeout = Duration.ofSeconds(30);

    /** 审批状态轮询间隔（R4/T4.2：1s 间隔 DB 轮询，多副本一致）。 */
    private Duration approvalPollInterval = Duration.ofSeconds(1);

    /** 审批等待上限（对齐 M4 审批 TTL 默认 PT15M）：超时 run 置 EXPIRED，审批单仍有效。 */
    private Duration approvalWaitLimit = Duration.ofMinutes(15);

}
