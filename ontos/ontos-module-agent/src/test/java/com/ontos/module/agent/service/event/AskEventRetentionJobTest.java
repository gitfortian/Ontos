package com.ontos.module.agent.service.event;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.ontos.module.agent.config.AskProperties;
import com.ontos.module.agent.dal.mysql.AskRunEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ask_run_event 保留策略单测（G-5，T5.3）：默认永久保留（与 audit_log 同规）不触发清理；
 * 显式配置保留天数时按 cutoff 清理并记录删除量。
 */
class AskEventRetentionJobTest {

    private final AskRunEventMapper mapper = mock(AskRunEventMapper.class);

    private AskEventRetentionJob job;

    private AskProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AskProperties();
        job = new AskEventRetentionJob(mapper, properties);
    }

    @Test
    void defaultPolicyNeverDeletes_g5() {
        // 默认 eventRetentionDays=0：永久保留（与 audit_log 同规）——不触发任何删除
        job.sweep();
        verify(mapper, never()).deleteOlderThan(any());
        assertEquals("永久保留（与 audit_log 同规，G-5 默认口径）", job.policyDescription());
    }

    @Test
    void explicitRetentionSweepsWithCutoff() {
        properties.setEventRetentionDays(30);
        when(mapper.deleteOlderThan(any())).thenReturn(42L);

        job.sweep();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(mapper).deleteOlderThan(cutoff.capture());
        // cutoff ≈ now(Asia/Shanghai) - 30 天，且带时区（TIMESTAMPTZ 语义，T53-1）
        OffsetDateTime expected = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).minusDays(30);
        assertTrue(!cutoff.getValue().isBefore(expected.minusSeconds(5)));
        assertTrue(!cutoff.getValue().isAfter(expected.plusSeconds(5)));
        assertEquals(ZoneId.of("Asia/Shanghai").getRules()
                .getOffset(cutoff.getValue().toInstant()), cutoff.getValue().getOffset());
    }

    @Test
    void negativeRetentionTreatedAsPermanent() {
        properties.setEventRetentionDays(-1);
        job.sweep();
        verify(mapper, never()).deleteOlderThan(any());
    }

}
