package com.ontos.module.agent.service.event;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.agent.dal.mysql.AskRunEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * run 事件服务（M5 规格 §3.5）：六类 AG-UI 事件的持久化（单调 seq）与 afterSeq 只读重放。
 *
 * <p>事件模型不自研（技术选型 §11 第 7 项）：类型与载荷形状遵循 AG-UI 协议
 * （RUN_STARTED / RUN_FINISHED / RUN_ERROR、TEXT_MESSAGE_*、TOOL_CALL_*、CUSTOM、STATE），
 * HITL interrupt 复用 M4 {@code HitlNotifier} 形态（type=INTERRUPT）。
 *
 * <p>断线重连幂等：重放是**只读**操作——{@code afterSeq} 之后的存量事件按 seq 升序返回，
 * 不触发任何工具调用/编排逻辑（重放注入探针覆盖）；重复重放结果逐字节一致。
 *
 * <p>{@code RUN_STARTED.input} 脱敏：服务端注入侧验证（PoC #10 判据）——
 * 身份三元组 / 审批令牌 / 角色列表等敏感键在持久化前抹除，前端 redact 之外的第二道防线。
 */
@Slf4j
@Service
public class AskEventService {

    /** AG-UI 事件类型（封闭枚举；payload 形状随类型冻结）。 */
    public static final String RUN_STARTED = "RUN_STARTED";
    public static final String RUN_FINISHED = "RUN_FINISHED";
    public static final String RUN_ERROR = "RUN_ERROR";
    public static final String TEXT_MESSAGE = "TEXT_MESSAGE_CONTENT";
    public static final String TOOL_CALL = "TOOL_CALL_END";
    public static final String CUSTOM = "CUSTOM";
    public static final String STATE = "STATE";
    public static final String INTERRUPT = "INTERRUPT";

    /** RUN_STARTED.input 的脱敏键（命中即整体抹除，值不保留部分形态）。 */
    private static final java.util.Set<String> REDACTED_KEYS = java.util.Set.of(
            "approvalid", "x-ontos-approval", "apikey", "api-key", "authorization",
            "roles", "userid", "user-id", "tenantid", "tenant-id", "cookie", "token");

    private final AskRunEventMapper eventMapper;

    public AskEventService(AskRunEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    /** 追加事件（持久化即权威；实时 SSE 下发由 M4 HitlNotifier 通道与 W4 内联复用）。 */
    public long emit(String runId, String type, Map<String, Object> payload) {
        Map<String, Object> safe = sanitize(type, payload);
        return eventMapper.append(runId, type, JsonUtils.toJsonString(safe));
    }

    /** afterSeq 只读重放：不触发任何执行，重复调用结果一致（重放幂等）。 */
    public List<Map<String, Object>> replayAfter(String runId, long afterSeq) {
        return eventMapper.replayAfter(runId, afterSeq);
    }

    /** 脱敏：RUN_STARTED 的 input 中身份/凭据类键抹除；其它类型原样。 */
    private Map<String, Object> sanitize(String type, Map<String, Object> payload) {
        if (payload == null) {
            return new LinkedHashMap<>();
        }
        if (!RUN_STARTED.equals(type)) {
            return payload;
        }
        Map<String, Object> safe = new LinkedHashMap<>(payload);
        Object input = safe.get("input");
        if (input instanceof Map<?, ?> inputBox) {
            Map<String, Object> redacted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : inputBox.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase();
                redacted.put(String.valueOf(entry.getKey()),
                        REDACTED_KEYS.contains(key) ? "[REDACTED]" : entry.getValue());
            }
            safe.put("input", redacted);
        }
        return safe;
    }

}
