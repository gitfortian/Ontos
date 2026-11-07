import { stream, type StreamEvent } from "@/service/http/request";

import type { AguiEvent } from "./types";

/**
 * AgentScope AG-UI 端点，实际路由是 `{agentscope.agui.path-prefix}/run/{agentId}`。
 * 路径写错不会报错只会 404，前端表现为「后端没起来」，排查成本很高，改这里前先 curl 验一次。
 */
export const AGUI_ENDPOINT = "/agent/run/ontos";

/** Standard AG-UI protocol events; the client never invents a streaming model. */
export const streamAgui = async (
  question: string,
  onEvent: (event: AguiEvent) => void,
  signal?: AbortSignal,
): Promise<void> => {
  const threadId = crypto.randomUUID();
  const runId = crypto.randomUUID();

  await stream(AGUI_ENDPOINT, {
    signal,
    data: {
      threadId,
      runId,
      messages: [{ id: crypto.randomUUID(), role: "user", content: question }],
      tools: [],
      context: [],
      state: {},
      forwardedProps: {},
    },
    onEvent: (event: StreamEvent) => onEvent(event as AguiEvent),
  });
};

export type { AguiEvent, AguiEventType } from "./types";
