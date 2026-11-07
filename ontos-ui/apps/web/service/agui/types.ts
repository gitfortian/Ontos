export interface AguiEvent {
  type: string;
  timestamp?: number;
  messageId?: string;
  role?: string;
  delta?: string;
  content?: string;
  toolCallName?: string;
  toolCallId?: string;
  [key: string]: unknown;
}

export type AguiEventType =
  | "RUN_STARTED"
  | "RUN_FINISHED"
  | "RUN_ERROR"
  | "TEXT_MESSAGE_START"
  | "TEXT_MESSAGE_CONTENT"
  | "TEXT_MESSAGE_END"
  | "TOOL_CALL_START"
  | "TOOL_CALL_ARGS"
  | "TOOL_CALL_END";
