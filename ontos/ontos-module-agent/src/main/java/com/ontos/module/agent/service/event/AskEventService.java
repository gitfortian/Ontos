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
    public static final String TOOL_CALL_START = "TOOL_CALL_START";
    public static final String TOOL_CALL = "TOOL_CALL_END";
    public static final String CUSTOM = "CUSTOM";
    public static final String STATE = "STATE";
    public static final String INTERRUPT = "INTERRUPT";

    /** CUSTOM 事件名：过程步骤（前端过程面板的数据源，不是前端推测的进度）。 */
    public static final String STEP_EVENT_NAME = "ontos.step";

    /** RUN_STARTED.input 的脱敏键（命中即整体抹除，值不保留部分形态）。 */
    private static final java.util.Set<String> REDACTED_KEYS = java.util.Set.of(
            "approvalid", "x-ontos-approval", "apikey", "api-key", "authorization",
            "roles", "userid", "user-id", "tenantid", "tenant-id", "cookie", "token");

    private final AskRunEventMapper eventMapper;

    /**
     * 实时下发通道（流式请求专用）：只在执行线程内绑定，emit 时同步推送同形事件。
     *
     * <p>用 ThreadLocal 而不是 runId→emitter 映射：runId 在执行线程内部才生成，
     * 请求线程无法提前注册；绑定点在 {@code executeTurnStreaming} 提交的任务内，
     * emit 与绑定同线程，语义等价且无并发字典清理问题。
     */
    private static final ThreadLocal<java.util.function.Consumer<Map<String, Object>>> LIVE_CHANNEL =
            new ThreadLocal<>();

    public AskEventService(AskRunEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    /** 绑定当前执行线程的实时通道（由流式入口在任务开始时调用）。 */
    public void bindLiveChannel(java.util.function.Consumer<Map<String, Object>> channel) {
        LIVE_CHANNEL.set(channel);
    }

    /** 解绑：必须在 finally 中调用，避免线程池复用时把旧连接带给下一次执行。 */
    public void clearLiveChannel() {
        LIVE_CHANNEL.remove();
    }

    /** 追加事件（持久化即权威；绑定实时通道时同步下发同形事件）。 */
    public long emit(String runId, String type, Map<String, Object> payload) {
        Map<String, Object> safe = sanitize(type, payload);
        // 先序列化再落库：实时下发复用同一份 JSON 文本，保证「实时与重放同形」
        String json = JsonUtils.toJsonString(safe);
        long seq = eventMapper.append(runId, type, json);
        java.util.function.Consumer<Map<String, Object>> channel = LIVE_CHANNEL.get();
        if (channel != null) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("seq", seq);
            event.put("type", type);
            event.put("payload", json);
            channel.accept(event);
        }
        return seq;
    }

    /** afterSeq 只读重放：不触发任何执行，重复调用结果一致（重放幂等）。 */
    public List<Map<String, Object>> replayAfter(String runId, long afterSeq) {
        return eventMapper.replayAfter(runId, afterSeq);
    }

    /**
     * 过程步骤事件（CUSTOM {@code ontos.step}）。
     *
     * <p>detail 只允许业务话术（如「命中 2 项：成交金额、大区」），**禁止**放资产 ID、
     * 物理表列名或 SQL——附录 A.4「ID 与文案分离」对事件通道同样生效。
     */
    public void emitStep(String runId, AskStep step, AskStep.Status status, String detail,
                         Long elapsedMs) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("stepKey", step.stepKey());
        value.put("title", step.title());
        value.put("status", status.value());
        if (detail != null) {
            value.put("detail", detail);
        }
        if (elapsedMs != null) {
            value.put("elapsedMs", elapsedMs);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", STEP_EVENT_NAME);
        event.put("value", value);
        emit(runId, CUSTOM, event);
    }

    /** 工具调用开始：argsSummary 是业务摘要（指标 / 维度 / 上限），不是 SQL 或物理列名。 */
    public void emitToolStart(String runId, String toolName, List<String> argsSummary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", toolName);
        payload.put("argsScope", "server_assembled");
        payload.put("argsSummary", argsSummary == null ? List.of() : argsSummary);
        emit(runId, TOOL_CALL_START, payload);
    }

    /** 工具调用结束：resultSummary 同样是业务摘要（行数 / 截断 / 只读连接），不含执行计划原文。 */
    public void emitToolEnd(String runId, String toolName, String status,
                            List<String> resultSummary, Long elapsedMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", toolName);
        payload.put("argsScope", "server_assembled");
        payload.put("status", status);
        payload.put("resultSummary", resultSummary == null ? List.of() : resultSummary);
        if (elapsedMs != null) {
            payload.put("elapsedMs", elapsedMs);
        }
        emit(runId, TOOL_CALL, payload);
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
