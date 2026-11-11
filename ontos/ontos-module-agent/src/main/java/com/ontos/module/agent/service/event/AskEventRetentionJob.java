package com.ontos.module.agent.service.event;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.ontos.module.agent.config.AskProperties;
import com.ontos.module.agent.dal.mysql.AskRunEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * ask_run_event 保留策略（G-5，M6/W5/T5.3 运维面）：与 audit_log 同规——默认**永久保留**
 * （只追加、审计/追责语义）；运维显式配置 {@code ontos.ask.event-retention-days > 0}
 * 时按扫描周期清理更早的事件行（v1.4 §8「事件表增长」风险台账的兜底闸门）。
 */
@Slf4j
@Service
public class AskEventRetentionJob {

    private final AskRunEventMapper mapper;
    private final AskProperties askProperties;

    public AskEventRetentionJob(AskRunEventMapper mapper, AskProperties askProperties) {
        this.mapper = mapper;
        this.askProperties = askProperties;
    }

    /** 保留扫描：retentionDays=0 时短路返回（永久保留，与 audit_log 同规）。 */
    @Scheduled(fixedDelayString = "#{T(java.time.Duration).parse('${ontos.ask.event-retention-sweep-interval:PT6H}').toMillis()}")
    public void sweep() {
        int retentionDays = askProperties.getEventRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        // T53-1（P3）：created_at 为 TIMESTAMPTZ——cutoff 用带时区的 OffsetDateTime，
        // 按上海墙钟（与系统时间口径一致），不依赖 JVM 默认时区
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"))
                .minusDays(retentionDays);
        long deleted = mapper.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("[retention][ask_run_event 清理: retentionDays={}, cutoff={}, deleted={}]",
                    retentionDays, cutoff, deleted);
        }
    }

    /** 供单测与运维面核对的有效策略描述。 */
    public String policyDescription() {
        Duration interval = askProperties.getEventRetentionSweepInterval();
        return askProperties.getEventRetentionDays() <= 0
                ? "永久保留（与 audit_log 同规，G-5 默认口径）"
                : "保留 " + askProperties.getEventRetentionDays() + " 天，扫描周期 " + interval;
    }

}
