package com.ontos.module.system.service.request;

import java.util.Map;
import java.util.Set;

/**
 * 请求级状态机（ADR-0004 2.2 的 8 态口径，以其状态表为准）。
 *
 * <p>状态只由服务端推进；终态（EXECUTED / REJECTED / EXPIRED / FAILED）不可回退；
 * 迁移合法性由本枚举封闭定义，非法迁移一律拒绝。
 */
public enum RequestStatus {

    RECEIVED,
    CLARIFYING,
    PROPOSED,
    APPROVAL_PENDING,
    EXECUTED,
    REJECTED,
    EXPIRED,
    FAILED;

    private static final Map<RequestStatus, Set<RequestStatus>> ALLOWED = Map.of(
            RECEIVED, Set.of(CLARIFYING, PROPOSED, REJECTED),
            CLARIFYING, Set.of(PROPOSED, EXPIRED, REJECTED),
            PROPOSED, Set.of(APPROVAL_PENDING, EXECUTED, REJECTED),
            APPROVAL_PENDING, Set.of(EXECUTED, REJECTED, EXPIRED));

    public boolean isTerminal() {
        return this == EXECUTED || this == REJECTED || this == EXPIRED || this == FAILED;
    }

    /** 迁移是否合法（终态无出边；未登记的起点一律非法）。 */
    public boolean canTransitionTo(RequestStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

}
