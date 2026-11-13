package com.ontos.module.agent.controller.admin.agui;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * 标准 AG-UI RunAgentInput（仅取本网关关心的字段）。
 *
 * <p>形态与 @ag-ui/client 的 HttpAgent 请求体一致；协议字段远多于此，
 * 未列出的字段（tools / state / context）本网关**不消费也不转发**——
 * 溯源网关只做协议形状转换，不做业务（ADR-0027）。
 */
@Schema(description = "AG-UI 标准运行输入（网关只取语义槽位）")
public record AguiRunInput(
        String threadId,
        String runId,
        List<AguiMessage> messages,
        Map<String, Object> forwardedProps) {

    public record AguiMessage(String id, String role, String content) {
    }

    /** 最后一条用户消息的文本（utterance）。 */
    public String utterance() {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            AguiMessage message = messages.get(i);
            if (message != null && "user".equals(message.role())
                    && message.content() != null && !message.content().isBlank()) {
                return message.content();
            }
        }
        return null;
    }

    /** 澄清裁决回传（前端经 forwardedProps 携带，网关只透传不解释）。 */
    public String clarificationChoice() {
        Object value = forwardedProps == null ? null : forwardedProps.get("clarificationChoice");
        return value == null ? null : String.valueOf(value);
    }

    /** 变体提示（同上，透传）。 */
    public String variantHint() {
        Object value = forwardedProps == null ? null : forwardedProps.get("variantHint");
        return value == null ? null : String.valueOf(value);
    }
}
