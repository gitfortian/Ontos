/** ask 终态与事件类型（M5 规格 §3.4/§3.5，载荷契约与后端 TerminalAssembler 对齐）。 */

/** 五终态 + HITL 等待中间态。 */
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
  matchedTerm: string;
}

/** 结构化终态载荷（CUSTOM ontos.terminal 的 value / POST /ask/runs 的 data）。 */
export interface AskTerminalVO {
  terminal: AskTerminal;
  requestId?: string;
  planRef?: string;
  reasonCode?: string;
  copy?: { text: string; templateId: string; fallback?: boolean };
  degraded?: boolean;
  body?: {
    rowCount?: number;
    rows?: Array<Record<string, unknown>>;
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
