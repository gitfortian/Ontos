package com.ontos.module.system.service.security;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.system.service.audit.AuditLogCreateReqDTO;
import com.ontos.module.system.service.audit.AuditService;
import org.springframework.stereotype.Service;

/**
 * 注入检查点桩实现（M4/W4 E5-②）：只做审计（action=INJECTION_SUSPECTED），
 * 不做拦截——拦截动作由 M5 编排决定。E5 验收判据：桩在单测中被调用并产出审计记录；
 * M5 编排接入时替换/包装本实现，接口签名不变。
 */
@Service
public class AuditBackedInjectionCheckpoint implements InjectionCheckpoint {

    /** 命中片段入审计前截断（审计行体积防线）。 */
    static final int SAMPLE_MAX_LENGTH = 200;

    private final AuditService auditService;

    public AuditBackedInjectionCheckpoint(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void onSuspected(RequestContext context, String source, String sample, String detail) {
        AuditLogCreateReqDTO audit = new AuditLogCreateReqDTO();
        audit.setRequestId(context.traceId());
        audit.setTenantId(context.tenantId());
        audit.setUserId(context.userId());
        audit.setSessionId(context.sessionId());
        audit.setTurnIndex(context.turnIndex());
        audit.setAction(InjectionCheckpoint.AUDIT_ACTION);
        audit.setMetricId(source);
        audit.setDslJson("{\"source\":\"" + escape(source) + "\",\"sample\":\""
                + escape(truncate(sample)) + "\",\"detail\":\"" + escape(detail) + "\"}");
        audit.setReasonCode(InjectionCheckpoint.AUDIT_ACTION);
        audit.setTraceId(context.traceId());
        audit.setDevMode(context.devMode());
        auditService.record(audit);
    }

    private static String truncate(String sample) {
        if (sample == null) {
            return "";
        }
        return sample.length() <= SAMPLE_MAX_LENGTH ? sample : sample.substring(0, SAMPLE_MAX_LENGTH);
    }

    /** 审计 JSON 字符串内的最小转义（引号与反斜杠）；换行折叠避免破坏行式日志。 */
    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "");
    }
}
