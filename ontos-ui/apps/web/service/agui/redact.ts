/** 只依赖事件自身结构的脱敏函数，不引入任何路径别名，便于 Node 直跑单测。 */
export interface AguiLikeEvent {
  type: string;
  threadId?: string;
  runId?: string;
  input?: unknown;
  [key: string]: unknown;
}

/**
 * 脱敏：`RUN_STARTED.input` 会回显完整请求体，其中 `messages[].content` 是用户原始问题。
 *
 * 该回显是 AG-UI 协议的正常行为（便于前端恢复会话），但一旦被写进浏览器日志、错误上报或埋点，
 * 等同于把用户问题全文外发。判据要求「在日志 / 错误上报路径中脱敏」，因此在事件进入 UI 之前替换，
 * 只保留可诊断的非敏感字段。
 *
 * 只处理 `RUN_STARTED`，其余事件原样返回——避免顺手改坏流式内容。
 */
export const redactAguiEvent = <T extends AguiLikeEvent>(event: T): T => {
  if (event.type !== "RUN_STARTED" || event.input == null) return event;
  const input = event.input as Record<string, unknown>;
  const messages = Array.isArray(input.messages) ? input.messages : [];
  const last = messages[messages.length - 1] as { content?: unknown } | undefined;
  return {
    ...event,
    input: {
      threadId: input.threadId ?? event.threadId,
      runId: input.runId ?? event.runId,
      messageCount: messages.length,
      // 只留长度不留内容：长度足以判断「是不是空消息」，内容不能留
      lastMessageLength: last ? String(last.content ?? "").length : 0,
    },
  };
};
