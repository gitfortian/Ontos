package com.ontos.module.system.service.approval;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * HITL 最小通道（M4/W4/T4.3，规格 §3.3）：敏感累计触发后向前端下发 AG-UI 格式的
 * INTERRUPT 事件（审批入口），决策经既有 REST（/api/v1/approvals/{id}/decide）回传。
 *
 * <p>这是「最小通道」：只做事件下发与订阅注册，不做对话内联等待——完整内联审批
 * 归 M5 编排接入（届时复用或替换本通知器，事件契约不变）。注册即订阅本租户+
 * 用户的审批事件；连接断开自动清理；下发失败只降级 WARN（审批单已在 DB，
 * 前端可用审批列表页兜底，通知丢失不构成安全问题）。
 */
@Slf4j
@Service
public class HitlNotifier {

    /** 订阅键：tenant|user。 */
    private final Map<String, SseEmitter> subscribers = new ConcurrentHashMap<>();

    /** 订阅审批事件流（SSE）。超时 0 = 不超时，由连接断开清理。 */
    public SseEmitter subscribe(RequestContext context) {
        SseEmitter emitter = new SseEmitter(0L);
        String key = key(context.tenantId(), context.userId());
        subscribers.put(key, emitter);
        Runnable cleanup = () -> subscribers.remove(key, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());
        log.info("[subscribe][HITL 订阅: {}]", key);
        return emitter;
    }

    /**
     * 下发 INTERRUPT 事件（AG-UI 事件形态）：审批入口事件，含审批单 ID 与触发上下文摘要。
     * 无订阅者（前端未打开）时静默跳过——审批单仍在，列表页可兜底。
     */
    public void notifyInterrupt(String tenantId, String userId, String approvalId,
                                String reasonCode, String sensitiveKey) {
        SseEmitter emitter = subscribers.get(key(tenantId, userId));
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name("INTERRUPT")
                    .data(Map.of("type", "INTERRUPT",
                            "approvalId", approvalId,
                            "reasonCode", reasonCode,
                            "sensitiveKey", sensitiveKey,
                            "decideEndpoint", "/api/v1/approvals/" + approvalId + "/decide")));
            log.info("[notifyInterrupt][HITL 事件已下发: {} approval={}]", key(tenantId, userId),
                    approvalId);
        } catch (IOException | IllegalStateException ex) {
            // 下发失败降级：审批单已落库，前端可从审批列表兜底，不阻断触发事务的调用方
            log.warn("[notifyInterrupt][HITL 事件下发失败（连接可能已断开）: {}]",
                    ex.toString());
            subscribers.remove(key(tenantId, userId), emitter);
        }
    }

    private static String key(String tenantId, String userId) {
        return tenantId + "|" + userId;
    }
}
