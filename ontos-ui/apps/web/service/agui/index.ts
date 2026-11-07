import { stream, type StreamEvent } from "@/service/http/request";

import type { AguiEvent } from "./types";

export const AGUI_ENDPOINT = "/agent/ontos";

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
