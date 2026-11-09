package com.ontos.module.session.service.session;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话状态配置（M5/W1）。
 */
@Data
@ConfigurationProperties(prefix = "ontos.session")
public class SessionProperties {

    /** recent_turns 保留深度（v1.1 F-6）：超出即裁剪最旧轮次。 */
    private int recentTurns = 5;
}
