import { useState } from "react";

import type { AskStepEntry, AskStepStatus } from "@/service/ask";

/**
 * 过程面板：按事件顺序逐条展示「做了什么、花了多久、结果如何」。
 *
 * <p>**没有总体进度条**：流程存在修复重试、澄清回跑、审批重放，不是线性推进；
 * 画百分比或「第 N / 共 M 步」会把循环与回退变成假象。
 * 同一步骤重复出现时保留历史条目并标注「第 N 次」，不做去重与补齐。
 */
const STATUS_META: Record<AskStepStatus, { icon: string; dot: string; text: string }> = {
  done: { icon: "✓", dot: "bg-success text-white", text: "完成" },
  running: { icon: "", dot: "border-2 border-primary bg-card", text: "进行中" },
  skipped: { icon: "–", dot: "border border-border bg-card text-muted-foreground", text: "跳过" },
  failed: { icon: "✕", dot: "bg-destructive text-white", text: "未通过" },
};

const formatElapsed = (elapsedMs?: number) =>
  typeof elapsedMs === "number" ? `${elapsedMs.toLocaleString("zh-CN")} ms` : null;

function StatusDot({ status }: { status: AskStepStatus }) {
  const meta = STATUS_META[status];
  return (
    <span
      aria-label={meta.text}
      className={`mt-0.5 flex size-3.5 shrink-0 items-center justify-center rounded-full text-[9px] leading-none ${meta.dot} ${
        status === "running" ? "animate-pulse" : ""
      }`}
    >
      {meta.icon}
    </span>
  );
}

export function ProcessPanel({ entries, running }: { entries: AskStepEntry[]; running: boolean }) {
  const [expanded, setExpanded] = useState(true);

  if (entries.length === 0 && !running) {
    return null;
  }

  return (
    <section className="rounded-xl border border-border bg-card">
      <header className="flex items-center gap-2 border-b border-border px-3.5 py-2.5">
        <h3 className="text-xs font-medium">执行过程</h3>
        <span className="text-[11px] text-muted-foreground">按服务端事件顺序</span>
        <span className="ml-auto text-[11px] text-muted-foreground">
          {entries.length > 0 ? `${entries.length} 条记录` : "等待事件"}
        </span>
        <button
          type="button"
          className="text-[11px] text-primary hover:underline"
          onClick={() => setExpanded((value) => !value)}
        >
          {expanded ? "收起过程" : "展开过程"}
        </button>
      </header>

      {expanded ? (
        <div className="px-3.5">
          {entries.length === 0 ? (
            <p className="py-3 text-xs text-muted-foreground">已发起，等待第一条过程事件…</p>
          ) : (
            entries.map((entry) => (
              <article
                key={`${entry.seq}-${entry.stepKey}-${entry.attempt}`}
                className={`flex gap-2.5 border-b border-border py-2.5 last:border-b-0 ${
                  entry.status === "failed" ? "-mx-3.5 bg-destructive/5 px-3.5" : ""
                }`}
              >
                <StatusDot status={entry.status} />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
                    <p
                      className={`text-xs ${entry.isTool ? "font-mono" : ""} ${
                        entry.status === "skipped" ? "text-muted-foreground" : ""
                      }`}
                    >
                      {entry.title}
                    </p>
                    {entry.attempt > 1 ? (
                      <span className="rounded border border-border px-1 text-[10px] text-muted-foreground">
                        第 {entry.attempt} 次
                      </span>
                    ) : null}
                    {formatElapsed(entry.elapsedMs) ? (
                      <span className="font-mono text-[11px] text-muted-foreground">
                        {formatElapsed(entry.elapsedMs)}
                      </span>
                    ) : null}
                    <span className="ml-auto text-[11px] text-muted-foreground">
                      {STATUS_META[entry.status].text}
                    </span>
                  </div>

                  {entry.isTool ? (
                    <div className="mt-1.5 rounded-lg border border-border bg-muted px-2.5 py-2">
                      <p className="text-[11px] text-muted-foreground">参数由服务端构造</p>
                      {entry.argsSummary && entry.argsSummary.length > 0 ? (
                        <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1">
                          {entry.argsSummary.map((item) => (
                            <span key={item} className="text-[11px] text-foreground">
                              {item}
                            </span>
                          ))}
                        </div>
                      ) : null}
                      {entry.resultSummary && entry.resultSummary.length > 0 ? (
                        <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1">
                          {entry.resultSummary.map((item) => (
                            <span key={item} className="text-[11px] text-muted-foreground">
                              {item}
                            </span>
                          ))}
                        </div>
                      ) : null}
                    </div>
                  ) : entry.detail ? (
                    <p className="mt-0.5 text-[11px] leading-relaxed text-muted-foreground">
                      {entry.detail}
                    </p>
                  ) : null}
                </div>
              </article>
            ))
          )}
        </div>
      ) : null}

      <footer className="border-t border-border px-3.5 py-1.5">
        <p className="text-[11px] leading-relaxed text-muted-foreground">
          同一步骤可重复出现（修复重试、审批重放）；按事件顺序排列，不代表固定流程。
        </p>
      </footer>
    </section>
  );
}
