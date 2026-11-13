import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";

import { HttpAgent } from "@ag-ui/client";
import { Button, TooltipProvider } from "@ontos/ui";
import { CopilotKitProvider, useAgent } from "@copilotkit/react-core/v2";

import { useAskWorkbenchStore } from "@/app/ask/stores/ask-workbench-store";

import { ProcessPanel } from "./process-panel";
import { ResultCard } from "./result-card";
import {
  ClarificationCard,
  CompositeCard,
  DiscoveryCard,
  OutOfCapabilityCard,
  WaitingCard,
} from "./terminal-cards";

/** 终态渲染：一个终态只对应一张卡片，不做多卡片拼接。 */
function TerminalView({
  terminal,
  stuckClarification,
  onChoose,
  disabled,
  onOpenApprovals,
  onTrace,
  onDrill,
}: {
  terminal: import("@/service/ask").AskTerminalVO;
  stuckClarification?: boolean;
  onChoose: (candidateId: string, matchedTerm: string) => void;
  disabled: boolean;
  onOpenApprovals: () => void;
  onTrace: () => void;
  onDrill: (utterance: string) => void;
}) {
  switch (terminal.terminal) {
    case "query":
      return (
        <ResultCard terminal={terminal} onTrace={onTrace} onDrill={onDrill} disabled={disabled} />
      );
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

/**
 * 问数工作台中栏：事件接入走 CopilotKit Headless（selfManagedAgents → /agent/ui/ontos，
 * ADR-0027）；状态归 Zustand（useAgent 订阅事件 → store.ingestAgui 归并）。
 */
function CopilotKitWorkspace() {
  const navigate = useNavigate();
  const ingestAgui = useAskWorkbenchStore((state) => state.ingestAgui);
  const { agent, isReady } = useAgent({ agentId: "ontos" });
  const [draft, setDraft] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const runs = useAskWorkbenchStore((state) => state.runs);
  const startTurn = useAskWorkbenchStore((state) => state.startTurn);
  const finishTurn = useAskWorkbenchStore((state) => state.finishTurn);
  const openTrace = useAskWorkbenchStore((state) => state.openTrace);

  useEffect(() => {
    if (!isReady || !agent) {
      return undefined;
    }
    const subscription = agent.subscribe({
      onEvent: (event) => {
        ingestAgui(event as unknown as Record<string, unknown>);
      },
    });
    return () => subscription.unsubscribe();
  }, [agent, isReady, ingestAgui]);

  const busy = useMemo(() => runs.some((turn) => turn.running) || submitting, [runs, submitting]);

  useEffect(() => {
    const node = scrollRef.current;
    if (node) {
      node.scrollTop = node.scrollHeight;
    }
  }, [runs]);

  const submit = async (utterance: string, clarificationChoice?: string, displayText?: string) => {
    if (!agent || busy) {
      return;
    }
    const { turnId, sessionId } = startTurn(utterance, displayText);
    setSubmitting(true);
    try {
      // AG-UI RunAgentParameters：threadId/messages 走实例字段，runId/forwardedProps 走参数
      agent.threadId = sessionId;
      agent.messages = [
        ...agent.messages,
        { id: crypto.randomUUID(), role: "user", content: utterance },
      ];
      await agent.runAgent({
        runId: crypto.randomUUID(),
        forwardedProps: { clarificationChoice: clarificationChoice ?? null },
      });
      finishTurn(turnId);
    } catch (error) {
      finishTurn(turnId, error instanceof Error ? error.message : String(error));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section className="flex min-h-0 flex-1 flex-col gap-3">
      <div ref={scrollRef} className="min-h-0 flex-1 space-y-4 overflow-y-auto pr-1">
        {runs.length === 0 ? (
          <div className="rounded-xl border border-dashed border-border px-4 py-8 text-center">
            <p className="text-xs text-muted-foreground">
              输入问题开始，例如「近 30 天各大区成交金额是多少」
            </p>
            <p className="mt-1.5 text-[11px] text-muted-foreground">
              每一步都来自服务端事件：理解、检索、编译、执行、审计可展开查看。
            </p>
          </div>
        ) : (
          runs.map((turn) => (
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
                        void submit(turn.utterance, candidateId, `已选择：${matchedTerm}`)
                      }
                      disabled={busy}
                      onOpenApprovals={() => navigate("/approvals")}
                      onTrace={() => openTrace(turn.terminal?.requestId ?? null, turn.terminal)}
                      onDrill={(utterance) => void submit(utterance)}
                    />
                  ) : null}

                  {turn.errorText ? (
                    <p className="rounded-lg border border-destructive/40 bg-destructive/5 px-3 py-2 text-[11px] text-destructive">
                      {turn.errorText}
                    </p>
                  ) : null}

                  {turn.running ? (
                    <span className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
                      <span className="size-1.5 animate-pulse rounded-full bg-primary" />
                      执行中
                    </span>
                  ) : null}
                </div>
              </div>
            </article>
          ))
        )}
      </div>

      <form
        className="flex items-center gap-2 rounded-xl border border-border bg-card px-2.5 py-2"
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

export function ChatPanel() {
  return (
    <CopilotKitProvider
      agentId="ontos"
      selfManagedAgents={{
        ontos: new HttpAgent({
          url: "/agent/ui/ontos",
          threadId: crypto.randomUUID(),
        }),
      }}
    >
      <TooltipProvider delayDuration={200}>
        <CopilotKitWorkspace />
      </TooltipProvider>
    </CopilotKitProvider>
  );
}
