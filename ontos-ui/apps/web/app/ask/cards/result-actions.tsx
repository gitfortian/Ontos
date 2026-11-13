import { useState } from "react";

import { ThumbsDown, ThumbsUp } from "lucide-react";

import {
  FEEDBACK_REASON_OPTIONS,
  submitAskFeedback,
  type AskFeedbackReasonCode,
} from "@/service/feedback";

import { toCsv } from "../result-summary";

type FeedbackState = "idle" | "pending" | "done" | "failed";

/**
 * 交互区（批复第 3 条）：👍👎（👎 带细分原因）+ 导出 CSV。
 * 反馈与后端端点同批交付——不发假请求、不伪装成功；提交失败如实显示。
 * 「存为报告」未做（挂账 P2），不渲染假按钮。
 */
export function ResultActions({
  requestId,
  rows,
  columns,
}: {
  requestId?: string;
  rows: Array<Record<string, unknown>>;
  columns: import("@/service/ask").AskColumnMeta[];
}) {
  const [state, setState] = useState<FeedbackState>("idle");
  const [showReasons, setShowReasons] = useState(false);

  const submit = async (rating: "UP" | "DOWN", reasonCode?: AskFeedbackReasonCode) => {
    if (!requestId || state === "pending") {
      return;
    }
    setState("pending");
    try {
      await submitAskFeedback({ requestId, rating, reasonCode });
      setState("done");
    } catch {
      setState("failed");
    }
  };

  const exportCsv = () => {
    const blob = new Blob([toCsv(rows, columns)], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `ontos-结果-${requestId ?? Date.now()}.csv`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  if (state === "done") {
    return (
      <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
        <span>已收到反馈，感谢</span>
        <button type="button" className="text-primary hover:underline" onClick={exportCsv}>
          导出 CSV
        </button>
      </div>
    );
  }

  return (
    <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5">
      {requestId ? (
        <>
          <span className="inline-flex items-center gap-1">
            <button
              type="button"
              aria-label="结果有用"
              disabled={state === "pending"}
              className="rounded-md border border-border p-1 text-muted-foreground hover:border-primary hover:text-primary disabled:opacity-50"
              onClick={() => void submit("UP")}
            >
              <ThumbsUp aria-hidden="true" size={13} />
            </button>
            <button
              type="button"
              aria-label="结果有问题"
              aria-expanded={showReasons}
              disabled={state === "pending"}
              className={`rounded-md border border-border p-1 text-muted-foreground hover:border-destructive hover:text-destructive disabled:opacity-50 ${
                showReasons ? "border-destructive text-destructive" : ""
              }`}
              onClick={() => setShowReasons((value) => !value)}
            >
              <ThumbsDown aria-hidden="true" size={13} />
            </button>
          </span>
          {showReasons ? (
            <span className="inline-flex flex-wrap items-center gap-1">
              {FEEDBACK_REASON_OPTIONS.map((option) => (
                <button
                  key={option.code}
                  type="button"
                  disabled={state === "pending"}
                  className="rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground hover:border-destructive hover:text-destructive disabled:opacity-50"
                  onClick={() => {
                    setShowReasons(false);
                    void submit("DOWN", option.code);
                  }}
                >
                  {option.label}
                </button>
              ))}
            </span>
          ) : null}
          {state === "failed" ? (
            <span className="text-[11px] text-destructive">反馈提交失败，可重试</span>
          ) : null}
        </>
      ) : null}
      <button
        type="button"
        className="text-[11px] text-primary hover:underline"
        onClick={exportCsv}
      >
        导出 CSV
      </button>
    </div>
  );
}
