import { useCallback, useMemo, useState } from "react";

import { Button } from "@ontos/ui";

import {
  createAskRun,
  replayAskEvents,
  type AskCandidate,
  type AskTerminalVO,
} from "@/service/ask";

/** 单轮对话记录：用户输入 + 终态载荷（或错误）。 */
interface ChatTurn {
  id: string;
  utterance: string;
  terminal?: AskTerminalVO;
  errorText?: string;
}

/** 终态卡片（T5.3）：query 结果 / 澄清四要素 / discovery / 拒答文案 / 审批等待。 */
function TerminalCard({
  turn,
  onClarify,
}: {
  turn: ChatTurn;
  onClarify: (candidateId: string) => void;
}) {
  const terminal = turn.terminal;
  if (!terminal) {
    return <p className="text-sm text-red-600">{turn.errorText}</p>;
  }
  const body = terminal.body ?? {};
  switch (terminal.terminal) {
    case "query":
      return (
        <div className="space-y-1">
          <p className="text-sm">
            查询完成，共 <span className="font-medium">{body.rowCount ?? 0}</span> 行
            {terminal.degraded ? "（确定性检索模式）" : ""}
          </p>
          {terminal.planRef ? (
            <a
              className="text-xs text-blue-600 underline"
              href={terminal.planRef}
              target="_blank"
              rel="noreferrer"
            >
              查看执行计划回放
            </a>
          ) : null}
        </div>
      );
    case "composite":
      return (
        <p className="text-sm">
          复合查询完成，{body.subResults?.length ?? 0} 个子结果（透传复合通道）
        </p>
      );
    case "clarification":
      return (
        <div className="space-y-2">
          <p className="text-sm">{body.ambiguity ?? "需要澄清"}</p>
          {(body.candidates ?? []).length > 0 ? (
            <div className="flex flex-wrap gap-2">
              {((body.candidates ?? []) as AskCandidate[]).map((candidate) => (
                <button
                  key={candidate.assetId}
                  type="button"
                  onClick={() => onClarify(candidate.assetId)}
                  className="rounded border px-2 py-1 text-xs hover:bg-accent"
                >
                  {candidate.matchedTerm}（{candidate.assetId}）
                </button>
              ))}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">
              请换一个更具体的说法，例如点名某个指标。
            </p>
          )}
        </div>
      );
    case "discovery":
      return (
        <div className="space-y-1 text-sm">
          <p>当前可查的能力：</p>
          <ul className="list-disc pl-5 text-xs text-muted-foreground">
            {((body.metrics ?? []) as AskCandidate[]).map((metric) => (
              <li key={metric.assetId}>
                {metric.matchedTerm}（{metric.assetId}）
              </li>
            ))}
            {((body.dimensions ?? []) as AskCandidate[]).map((dimension) => (
              <li key={dimension.assetId}>
                {dimension.matchedTerm}（{dimension.assetId}）
              </li>
            ))}
            {(body.metrics?.length ?? 0) + (body.dimensions?.length ?? 0) === 0 ? (
              <li>暂无命中的资产，请换个说法。</li>
            ) : null}
          </ul>
        </div>
      );
    case "out_of_capability":
      return (
        <div className="space-y-1">
          <p className="text-sm">{terminal.copy?.text ?? "该问题超出可回答范围。"}</p>
          {terminal.reasonCode ? (
            <p className="text-xs text-muted-foreground">原因码：{terminal.reasonCode}</p>
          ) : null}
        </div>
      );
    case "waiting":
      return (
        <div className="space-y-1">
          <p className="text-sm">审批等待中，决策完成后结果将出现在审批列表。</p>
          {body.approvalId ? (
            <p className="text-xs text-muted-foreground">审批单：{body.approvalId}</p>
          ) : null}
        </div>
      );
    default:
      return <p className="text-sm">{JSON.stringify(terminal)}</p>;
  }
}

/** ask 对话主干（T5.3）：轮次列表 + 终态渲染 + 澄清回传。审批决策经 M4 审批页完成。 */
export function ChatPanel() {
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = useCallback(async (utterance: string, clarificationChoice?: string) => {
    const id = `turn-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    setTurns((previous) => [
      ...previous,
      { id, utterance: clarificationChoice ? `（已选择：${clarificationChoice}）` : utterance },
    ]);
    setBusy(true);
    try {
      const terminal = await createAskRun({ utterance, clarificationChoice });
      setTurns((previous) =>
        previous.map((turn) => (turn.id === id ? { ...turn, terminal } : turn)),
      );
    } catch (error) {
      setTurns((previous) =>
        previous.map((turn) =>
          turn.id === id
            ? { ...turn, errorText: error instanceof Error ? error.message : String(error) }
            : turn,
        ),
      );
    } finally {
      setBusy(false);
    }
  }, []);

  // 等待中间态的 run：提供「查看事件」入口（afterSeq 重放只读，不触发执行）。
  const latestWaitingRunId = useMemo(() => {
    const waiting = [...turns].reverse().find((turn) => turn.terminal?.terminal === "waiting");
    return waiting?.terminal?.body?.approvalId ?? null;
  }, [turns]);

  const onClarify = useCallback(
    (candidateId: string) => {
      void submit("", candidateId);
    },
    [submit],
  );

  return (
    <section className="space-y-3 rounded-lg border p-4">
      <header className="space-y-1">
        <h2 className="text-base font-medium">对话</h2>
        <p className="text-xs text-muted-foreground">
          自然语言经编排链路返回结构化终态；审批触发时可在「审批」页决策。
        </p>
      </header>
      <div className="max-h-96 space-y-3 overflow-y-auto">
        {turns.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            输入问题开始对话，例如「帮我查一下销售额」。
          </p>
        ) : (
          turns.map((turn) => (
            <article key={turn.id} className="space-y-2 rounded border p-3">
              <p className="text-sm font-medium">{turn.utterance}</p>
              <TerminalCard turn={turn} onClarify={onClarify} />
            </article>
          ))
        )}
      </div>
      {latestWaitingRunId ? (
        <p className="text-xs text-muted-foreground">
          有审批等待中（{latestWaitingRunId}），可在「审批」页决策后重试。
        </p>
      ) : null}
      <form
        className="flex gap-2"
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
          className="flex-1 rounded border px-3 py-2 text-sm"
          value={draft}
          placeholder="例如：帮我查一下销售额"
          disabled={busy}
          onChange={(event) => setDraft(event.target.value)}
        />
        <Button type="submit" disabled={busy || draft.trim().length === 0}>
          {busy ? "执行中…" : "发送"}
        </Button>
      </form>
    </section>
  );
}

/** 事件重放查看（调试辅助）：按 runId 拉取只读事件序列。 */
export async function loadRunEvents(runId: string) {
  return replayAskEvents(runId);
}
