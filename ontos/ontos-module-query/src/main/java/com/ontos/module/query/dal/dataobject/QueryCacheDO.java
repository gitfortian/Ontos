package com.ontos.module.query.dal.dataobject;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 查询结果缓存 DO（M2/W4/T4.4，ADR-0008）。
 *
 * <p>缓存是可用性优化、不是合规边界：读写失败都只降级 WARN，不影响主响应。
 */
@Data
public class QueryCacheDO {

    /** SHA-256(规范化 JSON)，见 {@code QueryCacheKey}。 */
    private String cacheKey;
    private String manifestId;
    private String contentHash;
    /** 查询形态（分析模式），仅用于观测。 */
    private String queryShape;
    /** 结果 JSON 文本（原始 JSONB::text），由调用方序列化/反序列化。 */
    private String resultJson;
    private LocalDateTime createdAt;
    private LocalDateTime lastHitAt;
    private long hitCount;

}
