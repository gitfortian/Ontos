import { create } from "zustand";

import {
  type AskRunStreamEvent,
  type AskStepEntry,
  type AskTerminalVO,
  parsePayload,
  reduceStep,
  stepOf,
  streamAskRun,
  terminalOf,
  toolOf,
  toolStep,
} from "@/service/ask";

export interface AskRunTurn {
  id: string;
  sessionId: string;
  utterance: string;
  steps: AskStepEntry[];
  notes: string[];
  terminal?: AskTerminalVO;
  errorText?: string;
  running: boolean;
  /** 澄清回传后仍然是同一处歧义：页面要给明确出口（不掩盖后端语义缺陷）。 */
  stuckClarification?: boolean;
}

/** 上下文锚点：来自最近一轮 query 终态的 contextSummary（服务端下发，业务名）。 */
export interface AskContextChip {
  kind: "metric" | "dimension" | "time" | "mode" | "scenario";
  label: string;
}

interface AskWorkbenchState {
  drawerOpen: boolean;
  traceRequestId: string | null;
  activeSessionId: string | null;
  chips: AskContextChip[];
  runs: AskRunTurn[];
  aborters: Map<string, AbortController>;

  setDrawerOpen: (open: boolean) => void;
  openTrace: (requestId: string | null) => void;
  setActiveSession: (sessionId: string | null) => void;
  removeChip: (chip: AskContextChip) => void;
  clearRuns: () => void;
  stop: (turnId: string) => void;
  submit: (
    utterance: string,
    options?: { clarificationChoice?: string; displayText?: string },
  ) => Promise<void>;
}

const chipsFromTerminal = (terminal: AskTerminalVO): AskContextChip[] => {
  const summary = terminal.contextSummary;
  if (!summary) return [];
  const chips: AskContextChip[] = [];
  if (summary.metricLabel) chips.push({ kind: "metric", label: summary.metricLabel });
  for (const label of summary.dimensionLabels ?? []) {
    chips.push({ kind: "dimension", label });
  }
  if (summary.timeLabel) chips.push({ kind: "time", label: summary.timeLabel });
  if (summary.modeLabel) chips.push({ kind: "mode", label: summary.modeLabel });
  if (summary.scenarioLabel) chips.push({ kind: "scenario", label: summary.scenarioLabel });
  return chips;
};

const handleEventFor = (
  turnId: string,
  event: AskRunStreamEvent,
  set: (
    partial:
      | Partial<AskWorkbenchState>
      | ((state: AskWorkbenchState) => Partial<AskWorkbenchState>),
  ) => void,
) => {
  const patchTurn = (updater: (turn: AskRunTurn) => AskRunTurn) => {
    set((state) => ({
      runs: state.runs.map((turn) => (turn.id === turnId ? updater(turn) : turn)),
    }));
  };

  const step = stepOf(event);
  if (step) {
    patchTurn((turn) => ({ ...turn, steps: reduceStep(turn.steps, event.seq, step) }));
    return;
  }

  const tool = toolOf(event);
  if (tool) {
    const phase = event.type === "TOOL_CALL_START" ? "start" : "end";
    patchTurn((turn) => ({
      ...turn,
      steps: reduceStep(turn.steps, event.seq, toolStep(tool, phase)),
    }));
    return;
  }

  const terminal = terminalOf(event);
  if (terminal) {
    patchTurn((turn) => {
      const next = { ...turn, terminal };
      if (terminal.terminal === "query") {
        // chips 是「最近一轮生效口径」：终态更新即覆盖（服务端业务名，不本地推导）
        set({ chips: chipsFromTerminal(terminal) });
      }
      return {
        ...next,
        stuckClarification:
          terminal.terminal === "clarification" && turn.utterance.startsWith("已选择："),
      };
    });
    return;
  }

  if (event.type === "RUN_ERROR") {
    const payload = parsePayload<{ message?: string }>(event);
    patchTurn((turn) => ({
      ...turn,
      errorText: payload?.message ?? "执行失败",
      running: false,
    }));
    return;
  }

  if (event.type === "TEXT_MESSAGE_CONTENT") {
    const payload = parsePayload<{ delta?: string }>(event);
    const delta = payload?.delta;
    if (delta) patchTurn((turn) => ({ ...turn, notes: [...turn.notes, delta] }));
  }
};

/**
 * 问数工作台全局状态：
 * - 流式 run 运行态（高频事件更新）——按批复归 Zustand，不进 TanStack Query；
 * - 溯源抽屉开合 / 当前会话 / 上下文锚点——全局 UI 态。
 * 会话列表等 REST 数据仍归 TanStack Query（见 app/ask/hooks/use-sessions.ts）。
 */
export const useAskWorkbenchStore = create<AskWorkbenchState>((set, get) => ({
  drawerOpen: false,
  traceRequestId: null,
  activeSessionId: null,
  chips: [],
  runs: [],
  aborters: new Map<string, AbortController>(),

  setDrawerOpen: (drawerOpen) => set({ drawerOpen }),
  openTrace: (traceRequestId) => set({ traceRequestId, drawerOpen: true }),
  setActiveSession: (activeSessionId) => set({ activeSessionId, runs: [] }),
  removeChip: (chip) =>
    set((state) => ({
      chips: state.chips.filter((item) => item.kind !== chip.kind || item.label !== chip.label),
    })),
  clearRuns: () => {
    for (const aborter of get().aborters.values()) aborter.abort();
    set({ runs: [], chips: [] });
  },
  stop: (turnId) => {
    get().aborters.get(turnId)?.abort();
  },

  submit: async (utterance, options) => {
    const sessionId = get().activeSessionId ?? crypto.randomUUID();
    if (!get().activeSessionId) {
      set({ activeSessionId: sessionId });
    }
    const turn: AskRunTurn = {
      id: `turn-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      sessionId,
      utterance: options?.displayText ?? utterance,
      steps: [],
      notes: [],
      running: true,
    };
    set((state) => ({ runs: [...state.runs, turn] }));

    const aborter = new AbortController();
    get().aborters.set(turn.id, aborter);

    const onEvent = (event: AskRunStreamEvent) => handleEventFor(turn.id, event, set);
    try {
      await streamAskRun(
        { utterance, clarificationChoice: options?.clarificationChoice },
        onEvent,
        aborter.signal,
      );
    } catch (error) {
      const aborted = error instanceof DOMException && error.name === "AbortError";
      set((state) => ({
        runs: state.runs.map((item) =>
          item.id === turn.id
            ? {
                ...item,
                errorText: aborted
                  ? "已停止展示。服务端仍会执行到结束并记录审计，结果可在审计页查看。"
                  : error instanceof Error
                    ? error.message
                    : String(error),
              }
            : item,
        ),
      }));
    } finally {
      get().aborters.delete(turn.id);
      set((state) => ({
        runs: state.runs.map((item) => (item.id === turn.id ? { ...item, running: false } : item)),
      }));
    }
  },
}));
