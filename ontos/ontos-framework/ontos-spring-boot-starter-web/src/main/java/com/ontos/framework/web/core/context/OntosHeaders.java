package com.ontos.framework.web.core.context;

/**
 * 可信身份/上下文头。由认证网关解析凭证后注入并覆写；
 * 浏览器直连的本地开发模式才允许缺省。
 */
public final class OntosHeaders {

    public static final String TENANT = "X-Ontos-Tenant";
    public static final String USER = "X-Ontos-User";
    public static final String SESSION = "X-Ontos-Session";
    public static final String TURN = "X-Ontos-Turn";
    public static final String MANIFEST = "X-Ontos-Manifest";
    public static final String ROLES = "X-Ontos-Roles";
    public static final String REQUEST_ID = "X-Request-Id";
    /** 审批放行令牌 ID（M4/W2 T2.3）：敏感累计触发后携审批单放行的单次凭证。 */
    public static final String APPROVAL = "X-Ontos-Approval";

    private OntosHeaders() {
    }
}
