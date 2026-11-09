package com.ontos.module.system.service.sensitive;

import java.time.Duration;
import java.util.Map;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 跨轮敏感累计阈值与窗口（M4/W2，技术选型 §4.8.1 建议初值，ADR-0002 决策 3 不下调）。
 *
 * <p>初值冻结：L1(pii) 24h/T=8、L2(existence) 24h/T=4、L3(label) 7d/T=3。
 * 拒答档位按 §4.8.3 以 2T 计算（L1 ≥2T 或 L2/L3 ≥T 拒答），不单设配置。
 * 降级开关 {@code userWindowEnabled}（规格 §7 风险表）：仅允许压测确认 PG 为瓶颈时临时关闭；
 * 关闭期间 L1/L2/L3 查询强制走审批（不依赖计数），审计 reason_code 记 WINDOW_DEGRADED——
 * 禁止静默关闭跨会话规避检测，恢复需项目负责人确认。
 */
@Data
@ConfigurationProperties(prefix = "ontos.sensitive")
public class SensitiveProperties {

    /** 各敏感层的累计阈值 T（§4.8.1 初值，禁止下调代替 ADR-0002 修订）。 */
    private Map<String, Integer> thresholds = Map.of(
            "pii", 8,
            "existence", 4,
            "label", 3);

    /** 各敏感层的 user+tenant 级滑动窗口长度。 */
    private Map<String, Duration> windows = Map.of(
            "pii", Duration.ofHours(24),
            "existence", Duration.ofHours(24),
            "label", Duration.ofHours(24 * 7));

    /** user+tenant 级窗口开关（默认 true）：关闭即强制走审批（fail-safe 方向），见类注释。 */
    private boolean userWindowEnabled = true;

    public int thresholdOf(String sensitivityClass) {
        Integer threshold = thresholds.get(sensitivityClass);
        if (threshold == null || threshold <= 0) {
            // 配置缺失/非法按最严格处理：阈值 1（第二次同类访问即触发），绝不放大放行
            return 1;
        }
        return threshold;
    }

    public Duration windowOf(String sensitivityClass) {
        Duration window = windows.get(sensitivityClass);
        // 缺失按 24h 兜底（L1/L2 口径）；窗口只影响跨会话累计长度，不产生放行方向的偏差
        return window == null || window.isNegative() || window.isZero()
                ? Duration.ofHours(24) : window;
    }
}
