package com.ontos.module.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ask 运维面调度开关（M6/W5/T5.3）：启用 {@link com.ontos.module.agent.service.event.AskEventRetentionJob}
 * 的保留策略扫描。默认永久保留（G-5 与 audit_log 同规），扫描在 job 内短路，无运行时开销。
 */
@Configuration
@EnableScheduling
public class AskSchedulingConfiguration {

}
