import { useEffect, useRef } from "react";

import { useQuery } from "@tanstack/react-query";
import { sql } from "@codemirror/lang-sql";
import { defaultHighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { EditorState } from "@codemirror/state";
import { EditorView } from "@codemirror/view";

import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@ontos/ui";

import { useAskWorkbenchStore } from "@/app/ask/stores/ask-workbench-store";
import { replayPlan } from "@/service/query";

/** SQL 只读视图（CodeMirror 6）：体积约为 Monaco 的 1/10，只读高亮够用（批复第 3 条）。 */
function SqlView({ doc }: { doc: string }) {
  const containerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const node = containerRef.current;
    if (!node) {
      return undefined;
    }
    const theme = EditorView.theme({
      "&": { fontSize: "11px", background: "var(--muted)" },
      ".cm-content": {
        color: "var(--foreground)",
        fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
        padding: "8px 0",
      },
      "&.cm-focused": { outline: "none" },
    });
    const view = new EditorView({
      state: EditorState.create({
        doc,
        extensions: [
          sql(),
          syntaxHighlighting(defaultHighlightStyle),
          EditorView.lineWrapping,
          EditorState.readOnly.of(true),
          EditorView.editable.of(false),
          theme,
        ],
      }),
      parent: node,
    });
    return () => view.destroy();
  }, [doc]);

  return <div ref={containerRef} className="overflow-hidden rounded-lg border border-border" />;
}

/** 口径树行：label + value，全部业务名——物理名（表/列/ID）默认态不出现在抽屉里。 */
function CaliberRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-2 py-0.5 text-xs">
      <span className="w-16 shrink-0 text-muted-foreground">{label}</span>
      <span className="min-w-0 flex-1 break-words">{value}</span>
    </div>
  );
}

/**
 * 数据溯源抽屉（批复第 2 条）：口径树与关系路径始终可见；编译 SQL 按开发/调试
 * 环境闸门渲染（import.meta.env.DEV，生产构建整段不渲染）——服务端 plan 脱敏挂账 P2。
 * 默认不展示任何物理名（表/列/资产 ID）——这是默认态，不是降级。
 */
export function TraceDrawer() {
  const drawerOpen = useAskWorkbenchStore((state) => state.drawerOpen);
  const traceRequestId = useAskWorkbenchStore((state) => state.traceRequestId);
  const traceTerminal = useAskWorkbenchStore((state) => state.traceTerminal);
  const setDrawerOpen = useAskWorkbenchStore((state) => state.setDrawerOpen);

  const {
    data: plan,
    isPending,
    isError,
  } = useQuery({
    queryKey: ["ask", "trace", traceRequestId],
    queryFn: () => replayPlan(traceRequestId ?? ""),
    enabled: drawerOpen && Boolean(traceRequestId),
    staleTime: 5 * 60_000,
  });

  const summary = traceTerminal?.contextSummary;
  const hasCaliber = Boolean(
    summary && (summary.metricLabel || (summary.dimensionLabels?.length ?? 0) > 0),
  );

  return (
    <Sheet open={drawerOpen} onOpenChange={setDrawerOpen}>
      <SheetContent side="right" className="w-[420px] overflow-y-auto sm:max-w-[480px]">
        <SheetHeader>
          <SheetTitle>数据溯源</SheetTitle>
          <SheetDescription>
            本轮查询的口径构成与编译路径；内容来自服务端编译产物，只读。
          </SheetDescription>
        </SheetHeader>
        <div className="space-y-4 px-4 pb-6">
          <section className="rounded-lg border border-border p-3">
            <h3 className="mb-1.5 text-[11px] font-medium text-muted-foreground">口径构成</h3>
            {hasCaliber ? (
              <div>
                {summary?.metricLabel ? (
                  <CaliberRow label="指标" value={summary.metricLabel} />
                ) : null}
                {summary?.dimensionLabels?.length ? (
                  <CaliberRow label="维度" value={summary.dimensionLabels.join("、")} />
                ) : null}
                {summary?.timeLabel ? (
                  <CaliberRow label="时间窗" value={summary.timeLabel} />
                ) : null}
                {summary?.filterLabels?.length ? (
                  <CaliberRow label="过滤" value={summary.filterLabels.join("；")} />
                ) : null}
                {summary?.modeLabel ? <CaliberRow label="模式" value={summary.modeLabel} /> : null}
                {summary?.scenarioLabel ? (
                  <CaliberRow label="场景" value={summary.scenarioLabel} />
                ) : null}
              </div>
            ) : (
              <p className="text-[11px] text-muted-foreground">本轮没有可展示的口径快照。</p>
            )}
          </section>

          <section className="rounded-lg border border-border p-3">
            <h3 className="mb-1.5 text-[11px] font-medium text-muted-foreground">执行计划</h3>
            {isPending ? (
              <p className="text-[11px] text-muted-foreground">加载编译产物…</p>
            ) : isError ? (
              <p className="text-[11px] text-destructive">编译产物回放失败（可能已过期）。</p>
            ) : plan ? (
              <div>
                <CaliberRow
                  label="计划判定"
                  value={
                    plan.status === "BLOCKED"
                      ? `被拦截：${plan.blockReason ?? "成本或权限不满足"}`
                      : (plan.status ?? "已编译")
                  }
                />
                <CaliberRow label="行数上限" value={String(plan.appliedLimit ?? "—")} />
                {plan.estimatedRows != null ? (
                  <CaliberRow label="估算行数" value={plan.estimatedRows.toLocaleString("zh-CN")} />
                ) : null}
                <CaliberRow
                  label="关系路径"
                  value={
                    plan.joins.length === 0
                      ? "无需跨对象关联"
                      : plan.joins
                          .map(
                            (join) =>
                              `${join.relationId}（${join.direction}，${join.cardinality}）`,
                          )
                          .join(" → ")
                  }
                />
              </div>
            ) : (
              <p className="text-[11px] text-muted-foreground">当前没有可溯源的查询。</p>
            )}
          </section>

          {import.meta.env.DEV && plan?.sql ? (
            <section className="rounded-lg border border-border p-3">
              <h3 className="mb-1.5 text-[11px] font-medium text-muted-foreground">
                编译 SQL（仅开发/调试环境可见）
              </h3>
              <SqlView doc={plan.sql} />
              {plan.bindValues?.length ? (
                <p className="mt-1.5 text-[10px] text-muted-foreground">
                  绑定值：{plan.bindValues.map((value) => JSON.stringify(value)).join(", ")}
                </p>
              ) : null}
            </section>
          ) : null}

          {traceRequestId ? (
            <p className="font-mono text-[11px] text-muted-foreground">
              requestId {traceRequestId}
            </p>
          ) : null}
        </div>
      </SheetContent>
    </Sheet>
  );
}
