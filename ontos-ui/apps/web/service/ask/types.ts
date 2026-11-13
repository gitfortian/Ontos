/** ask 终态与事件类型（M5 规格 §3.4/§3.5，载荷契约与后端 TerminalAssembler 对齐）。 */

/** 终态名（六态：五类终态 + HITL 等待中间态）。 */
export type AskTerminal =
  | "query"
  | "composite"
  | "clarification"
  | "discovery"
  | "out_of_capability"
  | "waiting";

export interface AskCandidate {
  assetId: string;
  assetType: string;
  /** 检索词（用户叫法，可能与资产业务名不同或同名不同资产）。 */
  matchedTerm: string;
  /** 资产业务名（服务端从清单翻译；展示用它，assetId 只作回传值）。 */
  label?: string;
  /** 同一资产的其它叫法（同义词命中合并后保留）。 */
  aliases?: string[];
}

/**
 * 结果列元数据（服务端下发，前端不猜列名）。
 * `label` 是清单里的业务名（「成交金额」「大区」）；`key` 只用于取值，不上屏。
 */
export interface AskColumnMeta {
  key: string;
  label: string;
  role: "metric" | "dimension" | "step" | "conversion" | "cohort" | "other";
  numeric: boolean;
}

/** 上下文锚点（Context Chips）数据源：本轮生效口径，全部业务名，服务端下发。 */
export interface AskContextSummary {
  metricLabel?: string;
  dimensionLabels?: string[];
  timeLabel?: string;
  modeLabel?: string;
  scenarioLabel?: string;
}

/** 结构化终态载荷（CUSTOM ontos.terminal 的 value / POST /ask/runs 的 data）。 */
export interface AskTerminalVO {
  terminal: AskTerminal;
  requestId?: string;
  planRef?: string;
  reasonCode?: string;
  copy?: { text: string; templateId: string; fallback?: boolean };
  degraded?: boolean;
  contextSummary?: AskContextSummary;
  body?: {
    rowCount?: number;
    rows?: Array<Record<string, unknown>>;
    columns?: AskColumnMeta[];
    truncated?: boolean;
    elapsedMs?: number;
    subResults?: Array<Record<string, unknown>>;
    ambiguity?: string;
    candidates?: AskCandidate[];
    defaultSuggestion?: string | null;
    skippable?: boolean;
    metrics?: AskCandidate[];
    dimensions?: AskCandidate[];
    approvalId?: string | null;
    hint?: string;
  };
  note?: string;
}

/** 过程步骤（CUSTOM ontos.step 的 value）。同一步骤可重复出现，不是线性进度。 */
export interface AskStepPayload {
  stepKey: string;
  title: string;
  status: AskStepStatus;
  detail?: string;
  elapsedMs?: number;
}

export type AskStepStatus = "running" | "done" | "skipped" | "failed";

/** 工具调用载荷（TOOL_CALL_START / TOOL_CALL_END）。摘要只含业务话术，不含 SQL。 */
export interface AskToolPayload {
  toolName: string;
  argsScope?: string;
  argsSummary?: string[];
  resultSummary?: string[];
  status?: "succeeded" | "failed" | "closed" | string;
  elapsedMs?: number;
}

/** SSE 实时事件 / afterSeq 重放事件（同形：payload 为 JSON 文本）。 */
export interface AskRunStreamEvent {
  seq: number;
  type: string;
  payload: string;
}

/** 过程面板条目：步骤 + 同一 stepKey 的出现次序（前端计算，不来自服务端）。 */
export interface AskStepEntry extends AskStepPayload {
  seq: number;
  attempt: number;
  /** 工具条目的入参摘要（业务话术，服务端构造）。 */
  argsSummary?: string[];
  /** 工具条目的结果摘要（行数 / 截断 / 只读连接）。 */
  resultSummary?: string[];
  isTool?: boolean;
}

export interface AskRunRequest {
  utterance?: string;
  clarificationChoice?: string;
}

/** run 事件（afterSeq 重放；type 为 AG-UI 六类 + INTERRUPT）。 */
export interface AskRunEventVO {
  seq: number;
  type: string;
  payload: string;
  createdAt: string;
}
