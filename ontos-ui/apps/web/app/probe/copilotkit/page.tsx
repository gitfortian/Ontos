import { useCallback, useEffect, useMemo, useState } from "react";
import { CopilotKitProvider, useAgent } from "@copilotkit/react-core/v2";
import { HttpAgent } from "@ag-ui/client";
import { Alert, Badge, Button, Card, CardHeader, Textarea } from "@ontos/ui";

/**
 * T5.5 验证页：CopilotKit Headless 能否绕过 Node runtime，直接连 Java AG-UI SSE。
 *
 * 判据要求「不得引入 /api/copilotkit 类 Node runtime」。这里用
 * `agents__unsafe_dev_only` 注入一个 `HttpAgent` 指向后端 SSE 端点，
 * 不配置 `runtimeUrl`，然后观察：
 *   1. 会话能否跑通；
 *   2. 页面是否发出任何 `/api/copilotkit` 请求（有 = 主路线在浏览器直连前提下不成立）；
 *   3. 事件是否逐条到达（流式是否退化成批量）。
 *
 * 命名里的 `unsafe_dev_only` 本身就是结论的一部分：这是官方保留的非生产入口，
 * 生产可用性需要 M1 重新评估。
 */
const AGENT_ID = "ontos";
const ENDPOINT = "/agent/run/ontos";

interface TraceItem {
  at: number;
  text: string;
}

function createHttpAgent() {
  return new HttpAgent({
    url: ENDPOINT,
    agentId: AGENT_ID,
    threadId: `probe-${crypto.randomUUID()}`,
  });
}

function ProbeBody() {
  const { agent } = useAgent({ agentId: AGENT_ID });
  const [question, setQuestion] = useState("今年各区域的GMV是多少");
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [traces, setTraces] = useState<TraceItem[]>([]);
  const [messageCount, setMessageCount] = useState(0);
  const [answer, setAnswer] = useState("");

  // 只读 messages / isRunning 两个稳定公开属性，避免依赖细粒度回调签名
  useEffect(() => {
    const timer = window.setInterval(() => {
      setMessageCount(agent.messages.length);
      setRunning(agent.isRunning);
      const last = agent.messages[agent.messages.length - 1];
      if (last && typeof last.content === "string") setAnswer(last.content);
    }, 120);
    return () => window.clearInterval(timer);
  }, [agent]);

  const send = useCallback(async () => {
    if (!question.trim()) return;
    setError(null);
    setTraces([]);
    setAnswer("");
    const startedAt = performance.now();
    try {
      agent.messages = [{ id: crypto.randomUUID(), role: "user", content: question.trim() }];
      await agent.runAgent({ tools: [], context: [] });
      setTraces((previous) => [
        ...previous,
        { at: Math.round(performance.now() - startedAt), text: "runAgent 返回" },
      ]);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }, [agent, question]);

  return (
    <Card>
      <CardHeader
        title="CopilotKit Headless 直连验证（T5.5）"
        extra={<Badge tone={running ? "info" : "neutral"}>{running ? "运行中" : "空闲"}</Badge>}
      />
      <div className="space-y-3 p-4">
        <dl className="grid grid-cols-2 gap-2 text-xs text-[var(--muted-foreground)]">
          <div>
            <dt className="font-medium">端点</dt>
            <dd>{ENDPOINT}</dd>
          </div>
          <div>
            <dt className="font-medium">runtimeUrl</dt>
            <dd>未配置（故意）</dd>
          </div>
          <div>
            <dt className="font-medium">消息数</dt>
            <dd>{messageCount}</dd>
          </div>
          <div>
            <dt className="font-medium">协议</dt>
            <dd>AG-UI over SSE</dd>
          </div>
        </dl>

        <Textarea
          aria-label="验证问题"
          rows={2}
          value={question}
          onValueChange={setQuestion}
          placeholder="输入业务问题"
        />
        <div className="flex gap-2">
          <Button variant="primary" size="small" onClick={() => void send()} loading={running}>
            发送
          </Button>
        </div>

        {error ? <Alert>{error}</Alert> : null}

        <div className="min-h-16 rounded-[var(--radius)] bg-[var(--accent)] p-3 text-sm">
          {answer ? (
            <pre className="whitespace-pre-wrap break-words font-sans">{answer}</pre>
          ) : (
            <span className="text-[var(--muted-foreground)]">等待回答…</span>
          )}
        </div>

        {traces.length > 0 ? (
          <ul className="space-y-1 text-xs text-[var(--muted-foreground)]">
            {traces.map((trace, index) => (
              <li key={`trace-${index}`}>
                {trace.at} ms · {trace.text}
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    </Card>
  );
}

export function CopilotKitProbePage() {
  const agent = useMemo(() => createHttpAgent(), []);
  return (
    <CopilotKitProvider agents__unsafe_dev_only={{ [AGENT_ID]: agent }}>
      <ProbeBody />
    </CopilotKitProvider>
  );
}
