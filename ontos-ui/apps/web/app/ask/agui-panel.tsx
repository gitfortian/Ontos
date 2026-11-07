import { useMemo, useRef, useState } from "react";
import { Alert, Badge, Button, Card, CardHeader, Textarea } from "@ontos/ui";

import { streamAgui } from "@/service/agui";
import type { AguiEvent } from "@/service/agui";
import { failureMessage } from "@/utils/error-message";

import { AguiEventLog } from "./event-log";

type RunState = "idle" | "running" | "finished" | "error";

interface ToolCallTrace {
  id: string;
  name: string;
  args: string;
  result?: string;
}

/** 从事件流还原运行状态：以最后一个 RUN_* 为准。 */
function deriveRunState(events: readonly AguiEvent[]): RunState {
  for (let index = events.length - 1; index >= 0; index -= 1) {
    const type = events[index].type;
    if (type === "RUN_ERROR") return "error";
    if (type === "RUN_FINISHED") return "finished";
    if (type === "RUN_STARTED") return "running";
  }
  return "idle";
}

/** 按 toolCallId 聚合 TOOL_CALL_START / ARGS / RESULT，还原成可展示的调用链。 */
function deriveToolCalls(events: readonly AguiEvent[]): ToolCallTrace[] {
  const calls = new Map<string, ToolCallTrace>();
  for (const event of events) {
    const id = typeof event.toolCallId === "string" ? event.toolCallId : undefined;
    if (!id) continue;
    const current = calls.get(id) ?? { id, name: "", args: "" };
    if (event.type === "TOOL_CALL_START" && typeof event.toolCallName === "string") {
      current.name = event.toolCallName;
    }
    if (event.type === "TOOL_CALL_ARGS" && typeof event.delta === "string") {
      current.args += event.delta;
    }
    if (event.type === "TOOL_CALL_RESULT" && typeof event.content === "string") {
      current.result = event.content;
    }
    calls.set(id, current);
  }
  return [...calls.values()];
}

const STATE_LABEL: Record<
  RunState,
  { text: string; tone: "neutral" | "info" | "success" | "danger" }
> = {
  idle: { text: "待发送", tone: "neutral" },
  running: { text: "运行中", tone: "info" },
  finished: { text: "已完成", tone: "success" },
  error: { text: "出错", tone: "danger" },
};

export function AguiPanel() {
  const [question, setQuestion] = useState("今年各区域的 GMV 是多少？");
  const [events, setEvents] = useState<AguiEvent[]>([]);
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const controllerRef = useRef<AbortController | null>(null);

  const answer = events
    .filter((event) => event.type === "TEXT_MESSAGE_CONTENT")
    .map((event) => event.delta ?? "")
    .join("");

  const runState = deriveRunState(events);
  const toolCalls = useMemo(() => deriveToolCalls(events), [events]);
  const customEvents = useMemo(() => events.filter((event) => event.type === "CUSTOM"), [events]);
  const stateEvents = useMemo(
    () => events.filter((event) => event.type.startsWith("STATE_")),
    [events],
  );

  const send = async () => {
    if (!question.trim() || streaming) return;
    setEvents([]);
    setError(null);
    setStreaming(true);
    const controller = new AbortController();
    controllerRef.current = controller;
    try {
      await streamAgui(
        question.trim(),
        (event) => setEvents((previous) => [...previous, event]),
        controller.signal,
      );
    } catch (cause) {
      if (!controller.signal.aborted) setError(failureMessage(cause, "AG-UI 请求失败"));
    } finally {
      setStreaming(false);
      controllerRef.current = null;
    }
  };

  return (
    <Card>
      <CardHeader
        title="AG-UI 流式问答"
        extra={
          <div className="flex items-center gap-2">
            <Badge tone={STATE_LABEL[runState].tone}>{STATE_LABEL[runState].text}</Badge>
            <span className="text-xs text-[var(--muted-foreground)]">{events.length} 个事件</span>
          </div>
        }
      />
      <div className="space-y-3 p-4">
        <Textarea
          aria-label="业务问题"
          rows={3}
          value={question}
          onValueChange={setQuestion}
          placeholder="输入业务问题"
        />
        <div className="flex gap-2">
          <Button
            variant="primary"
            size="small"
            onClick={() => void send()}
            loading={streaming}
            disabled={!question.trim()}
          >
            发送
          </Button>
          <Button size="small" onClick={() => controllerRef.current?.abort()} disabled={!streaming}>
            中止
          </Button>
        </div>

        {error ? <Alert>{error}</Alert> : null}

        <div className="min-h-20 rounded-[var(--radius)] bg-[var(--accent)] p-3 text-sm text-[var(--foreground)]">
          {answer ? (
            <pre className="whitespace-pre-wrap break-words font-sans">{answer}</pre>
          ) : (
            <span className="text-[var(--muted-foreground)]">等待回答…</span>
          )}
        </div>

        {toolCalls.length > 0 ? (
          <section className="space-y-2">
            <h3 className="text-xs font-medium text-[var(--muted-foreground)]">
              工具调用（{toolCalls.length}）
            </h3>
            {toolCalls.map((call) => (
              <div
                key={call.id}
                className="rounded-[var(--radius)] border border-[var(--border)] p-2 text-xs"
              >
                <div className="font-medium text-[var(--foreground)]">{call.name || call.id}</div>
                {call.args ? (
                  <div className="mt-1 truncate text-[var(--muted-foreground)]">
                    参数 {call.args}
                  </div>
                ) : null}
                {call.result ? (
                  <div className="mt-1 truncate text-[var(--muted-foreground)]">
                    结果 {call.result}
                  </div>
                ) : null}
              </div>
            ))}
          </section>
        ) : null}

        {customEvents.length > 0 ? (
          <section className="space-y-1">
            <h3 className="text-xs font-medium text-[var(--muted-foreground)]">
              自定义事件（{customEvents.length}）
            </h3>
            <ul className="space-y-1 text-xs">
              {customEvents.map((event, index) => (
                <li key={`custom-${index}`} className="truncate text-[var(--muted-foreground)]">
                  {typeof event.name === "string" ? event.name : "CUSTOM"}
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {stateEvents.length > 0 ? (
          <section className="space-y-1">
            <h3 className="text-xs font-medium text-[var(--muted-foreground)]">
              状态事件（{stateEvents.length}）
            </h3>
            <ul className="space-y-1 text-xs">
              {stateEvents.map((event, index) => (
                <li key={`state-${index}`} className="truncate text-[var(--muted-foreground)]">
                  {event.type}
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        <details className="rounded-[var(--radius)] border border-[var(--border)] p-2">
          <summary className="cursor-pointer text-xs text-[var(--muted-foreground)]">
            原始事件流
          </summary>
          <div className="mt-2">
            <AguiEventLog events={events} />
          </div>
        </details>
      </div>
    </Card>
  );
}
