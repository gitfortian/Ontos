package com.ontos.module.system.service.approval;

import java.time.Duration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审批单生命周期配置（M4/W3/T3.1，规格 §3.3）。
 */
@Data
@ConfigurationProperties(prefix = "ontos.approval")
public class ApprovalProperties {

    /**
     * PENDING 单超时失效时长（默认 PT15M）：惰性过期 + 启动扫描共用；
     * 恰好等于 TTL 时按已过期处理（`>=` 边界，与快照 TTL 缓存同一口径）。
     */
    private Duration ttl = Duration.ofMinutes(15);
}
