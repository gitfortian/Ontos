import type {
  AskRunStreamEvent,
  AskStepEntry,
  AskStepPayload,
  AskStepStatus,
  AskTerminalVO,
  AskToolPayload,
} from "./types";

/** CUSTOM 事件名（终态与过程步骤都走 CUSTOM，靠 name 区分）。 */
export const STEP_EVENT_NAME = "ontos.step";
export const TERMINAL_EVENT_NAME = "ontos.terminal";
export const TOOL_STEP_PREFIX = "tool:";

/** 把 SSE 传输层拆出的对象规范成事件；payload 统一成 JSON 文本（与 afterSeq 重放同形）。 */
export const parseRunEvent = (raw: unknown): AskRunStreamEvent | null => {
  if (raw === null || typeof raw !== "object") return null;
  const record = raw as Record<string, unknown>;
  const type = typeof record.type === "string" ? record.type : "";
  if (!type) return null;
  const seq = typeof record.seq === "number" ? record.seq : 0;
  const payload =
    typeof record.payload === "string" ? record.payload : JSON.stringify(record.payload ?? {});
  return { seq, type, payload };
};

/**
 * 标准 AG-UI 扁平事件 → store 归并输入（W2/CopilotKit 接入层）。
 * 扁平事件的扩展字段（argsSummary / resultSummary / elapsedMs / label 等）
 * 由网关原样透传，这里只做形状包装，不改语义。
 */
export const fromAguiEvent = (flat: Record<string, unknown>): AskRunStreamEvent | null => {
  const type = typeof flat.type === "string" ? flat.type : "";
  if (!type) return null;
  const rest: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(flat)) {
    if (key !== "type" && key !== "seq") {
      rest[key] = value;
    }
  }
  const seq = typeof flat.seq === "number" ? flat.seq : 0;
  return { seq, type, payload: JSON.stringify(rest) };
};

/** 解析内嵌 payload 文本为对象（容错：坏 JSON 退回 null）。 */
export const parsePayload = <T>(event: AskRunStreamEvent): T | null => {
  try {
    return JSON.parse(event.payload) as T;
  } catch {
    return null;
  }
};

/** CUSTOM 事件的 name（非 CUSTOM 返回 null）。 */
export const customEventName = (event: AskRunStreamEvent): string | null => {
  if (event.type !== "CUSTOM") return null;
  const parsed = parsePayload<{ name?: unknown }>(event);
  return typeof parsed?.name === "string" ? parsed.name : null;
};

/** 过程步骤：CUSTOM + name=ontos.step。 */
export const stepOf = (event: AskRunStreamEvent): AskStepPayload | null => {
  if (customEventName(event) !== STEP_EVENT_NAME) return null;
  const parsed = parsePayload<{ value?: unknown }>(event);
  const value = parsed?.value;
  if (value === null || typeof value !== "object") return null;
  const record = value as Record<string, unknown>;
  if (typeof record.stepKey !== "string" || typeof record.title !== "string") return null;
  const status = record.status;
  if (status !== "running" && status !== "done" && status !== "skipped" && status !== "failed") {
    return null;
  }
  return {
    stepKey: record.stepKey,
    title: record.title,
    status,
    detail: typeof record.detail === "string" ? record.detail : undefined,
    elapsedMs: typeof record.elapsedMs === "number" ? record.elapsedMs : undefined,
  };
};

/** 终态：CUSTOM + name=ontos.terminal。 */
export const terminalOf = (event: AskRunStreamEvent): AskTerminalVO | null => {
  if (customEventName(event) !== TERMINAL_EVENT_NAME) return null;
  const parsed = parsePayload<{ value?: unknown }>(event);
  const value = parsed?.value;
  if (value === null || typeof value !== "object") return null;
  const record = value as Record<string, unknown>;
  return typeof record.terminal === "string" ? (value as AskTerminalVO) : null;
};

/**
 * 事件归属轮次（停止语义的防串轮闸）：
 * - 事件带 runId（RUN_STARTED/FINISHED/ERROR，enforce 不剥离）→ 只归属 runId 匹配的
 *   运行中轮次；该轮已停止/不存在则丢弃——被停止轮次的迟到事件绝不串到新问题；
 * - 事件不带 runId（CUSTOM/TOOL_CALL_*，enforce 剥离扩展字段）→ 归属最新运行中轮次
 *   （runs 串行：busy 闸保证同一时刻至多一个运行中轮）。
 */
export const resolveTurnForEvent = (
  runs: ReadonlyArray<{ id: string; runId?: string; running: boolean }>,
  eventRunId: unknown,
): { id: string } | null => {
  const running = runs.filter((turn) => turn.running);
  if (running.length === 0) {
    return null;
  }
  if (typeof eventRunId === "string" && eventRunId !== "") {
    return running.find((turn) => turn.runId === eventRunId) ?? null;
  }
  return running[running.length - 1];
};

/** 工具调用事件（TOOL_CALL_START / TOOL_CALL_END）。 */
export const toolOf = (
  event: AskRunStreamEvent,
): (AskToolPayload & { phase: "start" | "end" }) | null => {
  // AG-UI enforce 会剥离 TOOL_CALL_* 上的扩展字段——业务摘要走 CUSTOM ontos.tool；
  // 兼容同步端点的旧 TOOL_CALL_START/END 形态。
  if (event.type === "CUSTOM") {
    const parsed = parsePayload<{ name?: unknown; value?: unknown }>(event);
    if (parsed?.name !== "ontos.tool") return null;
    const value = parsed.value;
    if (value === null || typeof value !== "object") return null;
    const box = value as Record<string, unknown>;
    if (typeof box.toolName !== "string") return null;
    const list = (raw: unknown): string[] | undefined =>
      Array.isArray(raw)
        ? raw.filter((item): item is string => typeof item === "string")
        : undefined;
    return {
      toolName: box.toolName,
      argsSummary: list(box.argsSummary),
      resultSummary: list(box.resultSummary),
      status: typeof box.status === "string" ? box.status : undefined,
      elapsedMs: typeof box.elapsedMs === "number" ? box.elapsedMs : undefined,
      phase: box.phase === "start" ? "start" : "end",
    };
  }
  if (event.type !== "TOOL_CALL_START" && event.type !== "TOOL_CALL_END") return null;
  const record = parsePayload<Record<string, unknown>>(event);
  if (!record || typeof record.toolName !== "string") return null;
  const list = (value: unknown): string[] | undefined =>
    Array.isArray(value)
      ? value.filter((item): item is string => typeof item === "string")
      : undefined;
  return {
    toolName: record.toolName,
    argsScope: typeof record.argsScope === "string" ? record.argsScope : undefined,
    argsSummary: list(record.argsSummary),
    resultSummary: list(record.resultSummary),
    status: typeof record.status === "string" ? record.status : undefined,
    elapsedMs: typeof record.elapsedMs === "number" ? record.elapsedMs : undefined,
    phase: event.type === "TOOL_CALL_START" ? "start" : "end",
  };
};

const toolStatus = (phase: "start" | "end", status?: string): AskStepStatus => {
  if (phase === "start") return "running";
  if (status === "failed") return "failed";
  if (status === "closed") return "skipped";
  return "done";
};

/**
 * 工具事件转成过程条目：与步骤共用一条流，渲染层无需区分两套生命周期。
 * argsSummary 在 start 留存、resultSummary 在 end 补上——两次合并靠 stepKey（tool:xxx）。
 */
export const toolStep = (
  tool: AskToolPayload,
  phase: "start" | "end",
): Pick<
  AskStepEntry,
  | "stepKey"
  | "title"
  | "status"
  | "detail"
  | "elapsedMs"
  | "argsSummary"
  | "resultSummary"
  | "isTool"
> => ({
  stepKey: `${TOOL_STEP_PREFIX}${tool.toolName}`,
  title: tool.toolName,
  status: toolStatus(phase, tool.status),
  detail: (phase === "start" ? tool.argsSummary : tool.resultSummary)?.join(" · "),
  elapsedMs: tool.elapsedMs,
  argsSummary: tool.argsSummary,
  resultSummary: phase === "start" ? undefined : tool.resultSummary,
  isTool: true,
});

/**
 * 合并一条步骤 / 工具事件。
 *
 * <p>**不带进度语义**：同一步骤可重复出现（模型修复轮、审批重放）。规则只有一条——
 * 上一条同键条目仍是 running 就更新它，否则视为新一次（attempt+1），历史条目原样保留。
 */
export const reduceStep = (
  entries: AskStepEntry[],
  seq: number,
  incoming: Pick<
    AskStepEntry,
    | "stepKey"
    | "title"
    | "status"
    | "detail"
    | "elapsedMs"
    | "argsSummary"
    | "resultSummary"
    | "isTool"
  >,
): AskStepEntry[] => {
  let index = -1;
  for (let cursor = entries.length - 1; cursor >= 0; cursor -= 1) {
    if (entries[cursor].stepKey === incoming.stepKey) {
      index = cursor;
      break;
    }
  }
  if (index >= 0 && entries[index].status === "running") {
    const next = entries.slice();
    const previous = entries[index];
    next[index] = {
      ...previous,
      ...incoming,
      seq,
      attempt: previous.attempt,
      argsSummary: incoming.argsSummary ?? previous.argsSummary,
      resultSummary: incoming.resultSummary ?? previous.resultSummary,
    };
    return next;
  }
  let attempt = 1;
  for (const entry of entries) {
    if (entry.stepKey === incoming.stepKey) attempt += 1;
  }
  return [...entries, { ...incoming, seq, attempt }];
};
