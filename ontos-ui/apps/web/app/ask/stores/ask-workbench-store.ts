import { create } from "zustand";

import {
  fromAguiEvent,
  type AskStepEntry,
  type AskTerminalVO,
  parsePayload,
  reduceStep,
  stepOf,
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

  setDrawerOpen: (open: boolean) => void;
  openTrace: (requestId: string | null) => void;
  setActiveSession: (sessionId: string | null) => void;
  removeChip: (chip: AskContextChip) => void;
  clearRuns: () => void;

  /** 接入层入口：标准 AG-UI 扁平事件（CopilotKit useAgent 订阅转发）。 */
  ingestAgui: (flat: Record<string, unknown>) => void;
  /** 创建一轮对话（返回 turnId/sessionId；agent.runAgent 由组件触发）。 */
  startTurn: (utterance: string, displayText?: string) => { turnId: string; sessionId: string };
  finishTurn: (turnId: string, errorText?: string) => void;
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

export const useAskWorkbenchStore = create<AskWorkbenchState>((set, get) => ({
  drawerOpen: false,
  traceRequestId: null,
  activeSessionId: null,
  chips: [],
  runs: [],

  setDrawerOpen: (drawerOpen) => set({ drawerOpen }),
  openTrace: (traceRequestId) => set({ traceRequestId, drawerOpen: true }),
  setActiveSession: (activeSessionId) => set({ activeSessionId, runs: [] }),
  removeChip: (chip) =>
    set((state) => ({
      chips: state.chips.filter((item) => item.kind !== chip.kind || item.label !== chip.label),
    })),
  clearRuns: () => set({ runs: [], chips: [] }),

  ingestAgui: (wrapper) => {
    // CopilotKit 的 subscriber 回调是 {event, agent, input, ...} 包装——事件本体在 event 字段
    const flat = (wrapper?.event ?? wrapper) as Record<string, unknown>;
    const event = fromAguiEvent(flat);
    if (!event) return;
    const apply = (turnId: string, updater: (turn: AskRunTurn) => AskRunTurn) =>
      set((state) => ({
        runs: state.runs.map((turn) => (turn.id === turnId ? updater(turn) : turn)),
      }));
    set((state) => {
      const target = [...state.runs].reverse().find((turn) => turn.running);
      if (!target) {
        return {};
      }
      const step = stepOf(event);
      if (step) {
        apply(target.id, (turn) => ({ ...turn, steps: reduceStep(turn.steps, event.seq, step) }));
        return {};
      }
      const tool = toolOf(event);
      if (tool) {
        apply(target.id, (turn) => ({
          ...turn,
          steps: reduceStep(turn.steps, event.seq, toolStep(tool, tool.phase)),
        }));
        return {};
      }
      const terminal = terminalOf(event);
      if (terminal) {
        apply(target.id, (turn) => ({
          ...turn,
          terminal,
          stuckClarification:
            terminal.terminal === "clarification" && turn.utterance.startsWith("已选择："),
        }));
        if (terminal.terminal === "query") {
          return { chips: chipsFromTerminal(terminal) };
        }
        return {};
      }
      if (event.type === "RUN_ERROR") {
        const payload = parsePayload<{ message?: string }>(event);
        apply(target.id, (turn) => ({
          ...turn,
          errorText: payload?.message ?? "执行失败",
          running: false,
        }));
        return {};
      }
      if (event.type === "TEXT_MESSAGE_CONTENT") {
        const payload = parsePayload<{ delta?: string }>(event);
        const delta = payload?.delta;
        if (delta) apply(target.id, (turn) => ({ ...turn, notes: [...turn.notes, delta] }));
      }
      return {};
    });
  },

  startTurn: (utterance, displayText) => {
    const sessionId = get().activeSessionId ?? crypto.randomUUID();
    const turn: AskRunTurn = {
      id: `turn-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      sessionId,
      utterance: displayText ?? utterance,
      steps: [],
      notes: [],
      running: true,
    };
    set((previous) => ({
      runs: [...previous.runs, turn],
      activeSessionId: sessionId,
    }));
    return { turnId: turn.id, sessionId };
  },

  finishTurn: (turnId, errorText) =>
    set((state) => ({
      runs: state.runs.map((turn) =>
        turn.id === turnId
          ? { ...turn, running: false, errorText: errorText ?? turn.errorText }
          : turn,
      ),
    })),
}));
