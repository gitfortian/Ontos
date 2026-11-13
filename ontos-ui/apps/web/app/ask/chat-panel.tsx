import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";

import { Button } from "@ontos/ui";

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

import { ProcessPanel } from "./process-panel";
import { ResultCard } from "./result-card";
import {
  ClarificationCard,
  CompositeCard,
  DiscoveryCard,
  OutOfCapabilityCard,
  WaitingCard,
} from "./terminal-cards";

interface ChatTurn {
  id: string;
  utterance: string;
  steps: AskStepEntry[];
  notes: string[];
  terminal?: AskTerminalVO;
  errorText?: string;
  running: boolean;
  /** 澄清回传后仍然是同一处歧义：说明这次选择没缩小范围，页面要给明确出口。 */
  stuckClarification?: boolean;
}

const newTurn = (utterance: string): ChatTurn => ({
  id: `turn-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
  utterance,
  steps: [],
  notes: [],
  running: true,
});

/** 终态渲染：一个终态只对应一张卡片，不做多卡片拼接。 */
function TerminalView({
  terminal,
  stuckClarification,
  onChoose,
  disabled,
  onOpenApprovals,
}: {
  terminal: AskTerminalVO;
  stuckClarification?: boolean;
  onChoose: (candidateId: string, matchedTerm: string) => void;
  disabled: boolean;
  onOpenApprovals: () => void;
}) {
  switch (terminal.terminal) {
    case "query":
      return <ResultCard terminal={terminal} />;
    case "composite":
      return <CompositeCard terminal={terminal} />;
    case "clarification":
      return (
        <ClarificationCard
          terminal={terminal}
          onChoose={onChoose}
          disabled={disabled}
          stuck={stuckClarification}
        />
      );
    case "discovery":
      return <DiscoveryCard terminal={terminal} />;
    case "waiting":
      return <WaitingCard terminal={terminal} onOpenApprovals={onOpenApprovals} />;
    default:
      return <OutOfCapabilityCard terminal={terminal} />;
  }
}

/** ask 对话主干：流式事件驱动（过程面板 + 终态卡片），不再是一句「共 N 行」。 */
export function ChatPanel() {
  const navigate = useNavigate();
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [draft, setDraft] = useState("");
  const aborters = useRef(new Map<string, AbortController>());
  const scrollRef = useRef<HTMLDivElement | null>(null);

  const busy = useMemo(() => turns.some((turn) => turn.running), [turns]);

  const updateTurn = useCallback((turnId: string, updater: (turn: ChatTurn) => ChatTurn) => {
    setTurns((previous) => previous.map((turn) => (turn.id === turnId ? updater(turn) : turn)));
  }, []);

  const handleEvent = useCallback(
    (turnId: string, event: AskRunStreamEvent) => {
      const step = stepOf(event);
      if (step) {
        updateTurn(turnId, (turn) => ({
          ...turn,
          steps: reduceStep(turn.steps, event.seq, step),
        }));
        return;
      }

      const tool = toolOf(event);
      if (tool) {
        const phase = event.type === "TOOL_CALL_START" ? "start" : "end";
        updateTurn(turnId, (turn) => ({
          ...turn,
          steps: reduceStep(turn.steps, event.seq, toolStep(tool, phase)),
        }));
        return;
      }

      const terminal = terminalOf(event);
      if (terminal) {
        updateTurn(turnId, (turn) => ({
          ...turn,
          terminal,
          stuckClarification:
            terminal.terminal === "clarification" && turn.utterance.startsWith("已选择："),
        }));
        return;
      }

      if (event.type === "RUN_ERROR") {
        const payload = parsePayload<{ message?: string }>(event);
        updateTurn(turnId, (turn) => ({
          ...turn,
          errorText: payload?.message ?? "执行失败",
          running: false,
        }));
        return;
      }

      if (event.type === "TEXT_MESSAGE_CONTENT") {
        const payload = parsePayload<{ delta?: string }>(event);
        const delta = payload?.delta;
        if (delta) {
          updateTurn(turnId, (turn) => ({ ...turn, notes: [...turn.notes, delta] }));
        }
      }
    },
    [updateTurn],
  );

  const submit = useCallback(
    async (utterance: string, clarificationChoice?: string, displayText?: string) => {
      const turn = newTurn(displayText ?? utterance);
      setTurns((previous) => [...previous, turn]);

      const aborter = new AbortController();
      aborters.current.set(turn.id, aborter);

      try {
        await streamAskRun(
          { utterance, clarificationChoice },
          (event) => handleEvent(turn.id, event),
          aborter.signal,
        );
      } catch (error) {
        const aborted = error instanceof DOMException && error.name === "AbortError";
        updateTurn(turn.id, (previous) => ({
          ...previous,
          errorText: aborted
            ? "已停止展示。服务端仍会执行到结束并记录审计，结果可在审计页查看。"
            : error instanceof Error
              ? error.message
              : String(error),
        }));
      } finally {
        aborters.current.delete(turn.id);
        updateTurn(turn.id, (previous) => ({ ...previous, running: false }));
      }
    },
    [handleEvent, updateTurn],
  );

  const stop = useCallback((turnId: string) => {
    aborters.current.get(turnId)?.abort();
  }, []);

  const onChoose = useCallback(
    (utterance: string, candidateId: string, matchedTerm: string) => {
      // 回传候选时带上原话术：澄清只裁决歧义点，指标/维度仍来自原问题；
      // 只传 candidateId 会让下一轮丢失指标（实测产出「指标 未指定」的空 DSL）。
      void submit(utterance, candidateId, `已选择：${matchedTerm}`);
    },
    [submit],
  );

  useEffect(() => {
    const node = scrollRef.current;
    if (node) {
      node.scrollTop = node.scrollHeight;
    }
  }, [turns]);

  return (
    <section className="flex flex-col gap-3 rounded-xl border border-border bg-card p-4">
      <header className="flex items-start gap-2">
        <div>
          <h2 className="text-[13px] font-medium">对话</h2>
          <p className="mt-0.5 text-[11px] text-muted-foreground">
            每一步都来自服务端事件：理解、检索、编译、执行、审计都可展开查看。
          </p>
        </div>
        {turns.length > 0 ? (
          <Button
            type="button"
            variant="ghost"
            size="small"
            className="ml-auto"
            onClick={() => setTurns([])}
            disabled={busy}
          >
            清空
          </Button>
        ) : null}
      </header>

      <div ref={scrollRef} className="max-h-[70vh] space-y-4 overflow-y-auto pr-1">
        {turns.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border px-3 py-6 text-center">
            <p className="text-xs text-muted-foreground">
              输入问题开始，例如「近 30 天各大区成交金额是多少」
            </p>
          </div>
        ) : (
          turns.map((turn) => (
            <article key={turn.id} className="space-y-2.5">
              <div className="flex justify-end">
                <div className="max-w-[85%] rounded-xl rounded-br-sm bg-primary-subtle px-3 py-1.5">
                  <p className="text-xs leading-relaxed">{turn.utterance}</p>
                </div>
              </div>

              <div className="flex gap-2">
                <span className="flex size-6 shrink-0 items-center justify-center rounded-md bg-primary text-[10px] text-primary-foreground">
                  AI
                </span>
                <div className="min-w-0 flex-1 space-y-2">
                  {turn.notes.length > 0 ? (
                    <p className="text-[11px] text-muted-foreground">{turn.notes.join(" ")}</p>
                  ) : null}

                  <ProcessPanel entries={turn.steps} running={turn.running} />

                  {turn.terminal ? (
                    <TerminalView
                      terminal={turn.terminal}
                      stuckClarification={turn.stuckClarification}
                      onChoose={(candidateId, matchedTerm) =>
                        onChoose(turn.utterance, candidateId, matchedTerm)
                      }
                      disabled={busy}
                      onOpenApprovals={() => navigate("/approvals")}
                    />
                  ) : null}

                  {turn.errorText ? (
                    <p className="rounded-lg border border-destructive/40 bg-destructive/5 px-3 py-2 text-[11px] text-destructive">
                      {turn.errorText}
                    </p>
                  ) : null}

                  {turn.running ? (
                    <div className="flex items-center gap-3">
                      <span className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
                        <span className="size-1.5 animate-pulse rounded-full bg-primary" />
                        执行中
                      </span>
                      <button
                        type="button"
                        className="text-[11px] text-primary hover:underline"
                        onClick={() => stop(turn.id)}
                      >
                        停止展示
                      </button>
                    </div>
                  ) : null}
                </div>
              </div>
            </article>
          ))
        )}
      </div>

      <form
        className="flex items-center gap-2 rounded-xl border border-border px-2.5 py-2"
        onSubmit={(event) => {
          event.preventDefault();
          const utterance = draft.trim();
          if (!utterance || busy) {
            return;
          }
          setDraft("");
          void submit(utterance);
        }}
      >
        <input
          className="flex-1 bg-transparent px-1 text-xs outline-none placeholder:text-muted-foreground"
          value={draft}
          placeholder="例如：近 30 天各大区成交金额是多少"
          disabled={busy}
          onChange={(event) => setDraft(event.target.value)}
        />
        <Button
          type="submit"
          variant="primary"
          size="small"
          disabled={busy || draft.trim().length === 0}
        >
          {busy ? "执行中" : "发送"}
        </Button>
      </form>
    </section>
  );
}
