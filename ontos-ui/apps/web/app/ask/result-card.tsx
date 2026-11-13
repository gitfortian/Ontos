import type { AskColumnMeta, AskTerminalVO } from "@/service/ask";

import {
  buildBars,
  formatCellValue,
  orderColumnsForDisplay,
  summarizeResult,
} from "./result-summary";

/**
 * 结果卡片：表头、图表、数值全部来自服务端下发的列元数据（business label + role），
 * 前端不猜列名、不编造增长率——没有对照数据就不显示增长。
 */
function ResultTable({
  rows,
  columns,
}: {
  rows: Array<Record<string, unknown>>;
  columns: AskColumnMeta[];
}) {
  const fallbackColumns: AskColumnMeta[] =
    columns.length > 0
      ? orderColumnsForDisplay(columns)
      : Object.keys(rows[0] ?? {}).map((key) => ({
          key,
          label: key,
          role: "other" as const,
          numeric: false,
        }));

  return (
    <div className="mt-3 max-h-72 overflow-auto rounded-lg border border-border">
      <table className="w-full border-collapse text-xs">
        <thead className="sticky top-0 bg-muted">
          <tr>
            {fallbackColumns.map((column) => (
              <th
                key={column.key}
                className={`whitespace-nowrap border-b border-border px-2.5 py-1.5 font-normal text-muted-foreground ${
                  column.numeric ? "text-right" : "text-left"
                }`}
              >
                {column.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={index} className="border-b border-border last:border-b-0">
              {fallbackColumns.map((column) => (
                <td
                  key={column.key}
                  className={`px-2.5 py-1.5 ${column.numeric ? "text-right font-mono tabular-nums" : ""}`}
                >
                  {formatCellValue(row[column.key])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function BarChart({
  rows,
  columns,
}: {
  rows: Array<Record<string, unknown>>;
  columns: AskColumnMeta[];
}) {
  const { bars, truncated } = buildBars(rows, columns);
  if (bars.length === 0) {
    return null;
  }
  const max = Math.max(...bars.map((bar) => bar.value), 1);
  return (
    <div className="mt-3 rounded-lg border border-border p-3">
      <div className="space-y-1.5">
        {bars.map((bar) => (
          <div key={bar.label} className="flex items-center gap-2">
            <span
              className="w-20 shrink-0 truncate text-[11px] text-muted-foreground"
              title={bar.label}
            >
              {bar.label}
            </span>
            <div className="h-3 flex-1 overflow-hidden rounded-sm bg-muted">
              <div
                className="h-3 rounded-sm bg-primary"
                style={{ width: `${Math.max((bar.value / max) * 100, 2)}%` }}
              />
            </div>
            <span className="w-20 shrink-0 text-right font-mono text-[11px] tabular-nums">
              {formatCellValue(bar.value)}
            </span>
          </div>
        ))}
      </div>
      {truncated ? (
        <p className="mt-2 text-[11px] text-muted-foreground">仅展示前 12 项，完整数据见下表</p>
      ) : null}
    </div>
  );
}

export function ResultCard({
  terminal,
  onTrace,
}: {
  terminal: AskTerminalVO;
  onTrace?: () => void;
}) {
  const body = terminal.body ?? {};
  const rows = body.rows ?? [];
  const columns = body.columns ?? [];
  const summary = summarizeResult(rows, columns);
  const elapsedMs = body.elapsedMs;

  return (
    <section className="rounded-xl border border-border bg-card p-4" aria-label="查询结果">
      <header className="flex items-baseline justify-between gap-3">
        <h2 className="text-[13px] font-medium">
          {summary.metricLabel ?? "查询结果"}
          {summary.dimensionLabels.length > 0 ? ` · 按${summary.dimensionLabels.join("、")}` : ""}
        </h2>
        {terminal.degraded ? (
          <span className="text-[11px] text-warning">确定性检索模式</span>
        ) : null}
      </header>

      <div className="mt-3 grid gap-2 sm:grid-cols-3">
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

      <BarChart rows={rows} columns={columns} />
      <ResultTable rows={rows} columns={columns} />

      <footer className="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-border pt-2">
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
        {terminal.requestId ? (
          <span className="ml-auto font-mono text-[11px] text-muted-foreground">
            {terminal.requestId}
          </span>
        ) : null}
      </footer>
    </section>
  );
}
