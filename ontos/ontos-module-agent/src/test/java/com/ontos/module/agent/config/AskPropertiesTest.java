package com.ontos.module.agent.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ask 运维面配置单测（M6/W5/T5.3）：线程池扩容 + G-5 保留策略默认口径的回归锁。
 */
class AskPropertiesTest {

    @Test
    void runtimeDefaults_t53() {
        AskProperties properties = new AskProperties();
        // 扩容（M5 验收报告 §5 移交项）：审批轮询 IO 密集型，默认 4 → 16
        assertEquals(16, properties.getRunExecutorPool());
        // G-5：默认永久保留（与 audit_log 同规），运维显式配置天数才启用清理
        assertEquals(0, properties.getEventRetentionDays());
        assertEquals(Duration.ofHours(6), properties.getEventRetentionSweepInterval());
    }

    @Test
    void retentionCanBeEnabledByOperator() {
        AskProperties properties = new AskProperties();
        properties.setEventRetentionDays(30);
        properties.setEventRetentionSweepInterval(Duration.ofHours(12));
        assertEquals(30, properties.getEventRetentionDays());
        assertEquals(Duration.ofHours(12), properties.getEventRetentionSweepInterval());
    }

}
