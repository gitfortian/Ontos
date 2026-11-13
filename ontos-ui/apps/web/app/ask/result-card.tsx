import { UiBadge, Tabs, TabsContent, TabsList, TabsTrigger } from "@ontos/ui";

import type { AskTerminalVO } from "@/service/ask";

import { buildChartData, buildInsights, formatCellValue, summarizeResult } from "./result-summary";
import { InsightSummary } from "./cards/insight-summary";
import { ResultChart } from "./cards/result-chart";
import { ResultActions } from "./cards/result-actions";
import { ResultTable } from "./cards/result-table";

/**
 * 结果看板（批复第 3 条：从纯文本升级为数据看板）——
 * 摘要区（确定性洞察）/ 统计条 / 可视化区（ECharts ⇄ TanStack Table 切换）/ 交互区（反馈+导出）。
 * 表头、图表、数值全部来自服务端下发的列元数据，前端不猜列名、不编造增长率。
 */
export function ResultCard({
  terminal,
  onTrace,
  onDrill,
  disabled,
}: {
  terminal: AskTerminalVO;
  onTrace?: () => void;
  onDrill?: (utterance: string) => void;
  disabled?: boolean;
}) {
  const body = terminal.body ?? {};
  const rows = body.rows ?? [];
  const columns = body.columns ?? [];
  const summary = summarizeResult(rows, columns);
  const insights = buildInsights(rows, columns);
  const chartData = buildChartData(rows, columns);
  const elapsedMs = body.elapsedMs;

  return (
    <section className="rounded-xl border border-border bg-card p-4" aria-label="查询结果">
      <header className="flex items-center justify-between gap-3">
        <h2 className="text-[13px] font-medium">
          {summary.metricLabel ?? "查询结果"}
          {summary.dimensionLabels.length > 0 ? ` · 按${summary.dimensionLabels.join("、")}` : ""}
        </h2>
        {terminal.degraded ? (
          <UiBadge variant="outline" className="text-[11px] font-normal">
            确定性检索模式
          </UiBadge>
        ) : null}
      </header>

      {insights.length > 0 ? (
        <div className="mt-2.5">
          <InsightSummary insights={insights} />
        </div>
      ) : null}

      <div className="mt-2.5 grid gap-2 sm:grid-cols-3">
        <div className="rounded-lg bg-muted px-2.5 py-2">
          <p className="text-[11px] text-muted-foreground">
            {summary.metricLabel ? `${summary.metricLabel} 合计` : "指标合计"}
          </p>
          <p className="mt-0.5 text-lg font-medium">
            {summary.total === null ? "—" : formatCellValue(summary.total)}
          </p>
        </div>
        <div className="rounded-lg bg-muted px-2.5 py-2">
          <p className="text-[11px] text-muted-foreground">返回行数</p>
          <p className="mt-0.5 text-lg font-medium">{body.rowCount ?? rows.length}</p>
        </div>
        <div className="rounded-lg bg-muted px-2.5 py-2">
          <p className="text-[11px] text-muted-foreground">查询耗时</p>
          <p className="mt-0.5 text-lg font-medium">
            {typeof elapsedMs === "number" ? `${elapsedMs} ms` : "—"}
          </p>
        </div>
      </div>

      {chartData ? (
        <Tabs defaultValue="chart" className="mt-3">
          <TabsList className="h-7">
            <TabsTrigger value="chart" className="px-2.5 py-1 text-[11px]">
              图表
            </TabsTrigger>
            <TabsTrigger value="table" className="px-2.5 py-1 text-[11px]">
              明细
            </TabsTrigger>
          </TabsList>
          <TabsContent value="chart" className="mt-2">
            <ResultChart data={chartData} disabled={disabled} onDrill={onDrill} />
          </TabsContent>
          <TabsContent value="table" className="mt-2">
            <ResultTable rows={rows} columns={columns} />
          </TabsContent>
        </Tabs>
      ) : (
        <div className="mt-3">
          <ResultTable rows={rows} columns={columns} />
        </div>
      )}

      <footer className="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1.5 border-t border-border pt-2">
        <ResultActions requestId={terminal.requestId} rows={rows} columns={columns} />
        {onTrace ? (
          <button
            type="button"
            className="text-[11px] text-primary hover:underline"
            onClick={onTrace}
          >
            数据溯源
          </button>
        ) : null}
        {terminal.planRef ? (
          <a
            className="text-[11px] text-primary hover:underline"
            href={terminal.planRef}
            target="_blank"
            rel="noreferrer"
          >
            执行计划回放
          </a>
        ) : null}
        <span className="text-[11px] text-muted-foreground">
          {body.truncated ? "已按上限截断" : "未截断"}
        </span>
      </footer>
    </section>
  );
}
