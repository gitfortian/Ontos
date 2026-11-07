import { useRef, useState } from "react";
import { Alert, Button, Card, CardHeader, Textarea } from "@ontos/ui";

import { streamAgui } from "@/service/agui";
import type { AguiEvent } from "@/service/agui";
import { failureMessage } from "@/utils/error-message";

import { AguiEventLog } from "./event-log";

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
          <span className="text-xs text-[var(--muted-foreground)]">{events.length} 个事件</span>
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
            <pre className="whitespace-pre-wrap font-sans">{answer}</pre>
          ) : (
            <span className="text-[var(--muted-foreground)]">等待回答…</span>
          )}
        </div>

        <AguiEventLog events={events} />
      </div>
    </Card>
  );
}
