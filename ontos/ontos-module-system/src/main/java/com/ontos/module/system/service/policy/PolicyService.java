package com.ontos.module.system.service.policy;

import com.ontos.framework.web.core.context.RequestContext;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.enums.GlobalErrorCodeConstants.FORBIDDEN;
import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 策略判定入口。默认 fail-closed；本地开发可通过 ontos.policy.dev-allow-all 临时放行。
 *
 * <p>TODO 阶段一：接入 System DB 的 policy_snapshot（用户/租户/角色/数据域/域组合策略），
 * 与数据库 RLS 形成“应用层 + DB 层”双边界。任何情况下都不得由用户文本决定身份或权限。
 */
@Service
public class PolicyService {

    private final PolicyProperties properties;

    public PolicyService(PolicyProperties properties) {
        this.properties = properties;
    }

    public void check(RequestContext context, String assetId) {
        if (properties.isDevAllowAll()) {
            return;
        }
        throw exception(FORBIDDEN);
    }

}
