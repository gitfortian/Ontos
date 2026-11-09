package com.ontos.module.system.dal.dataobject;

import lombok.Data;

/**
 * 跨轮敏感累计事件行（§4.8 冻结清单 + 差分判定必需的 filters_canonical 实现列）。
 */
@Data
public class SensitiveQueryEventDO {

    private Long id;
    private String requestId;
    private String tenantId;
    private String userId;
    private String sessionId;
    /** {asset_id}#{sensitivity_class}，§4.8.1 取数键。 */
    private String sensitiveKey;
    /** pii / existence / label（L1/L2/L3）。 */
    private String sensitivityClass;
    /** 授权域哈希：SHA-256(userId|排序角色集)。 */
    private String scopeHash;
    /** 规范化 filters 序列化的 SHA-256。 */
    private String filtersHash;
    /** 规范化 filters 序列化明文（换行分隔三元组），ADR-0002 差分判定输入。 */
    private String filtersCanonical;
    private Integer resultCardinality;
    /** plain / differential_instant / individual_probe。 */
    private String patternTag;
    private String occurredAt;
    private String reasonCode;
    private String contextDelta;
}
