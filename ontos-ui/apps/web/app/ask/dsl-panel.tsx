import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  Alert,
  Badge,
  Button,
  Card,
  CardHeader,
  Field,
  FieldLabel,
  Input,
  Table,
  type TableColumns,
} from "@ontos/ui";

import { getCurrentManifest, manifestQueryKeys } from "@/service/ontology";
import {
  buildQueryDsl,
  compileQuery,
  dryPlanQuery,
  dryRunQuery,
  executeQuery,
} from "@/service/query";
import type { DslDraft, DryRunVO, PlanVO } from "@/service/query";
import { failureMessage } from "@/utils/error-message";

const HORIZONTAL_FIELD_CLASS = "grid grid-cols-[104px_minmax(0,1fr)] items-start gap-2";

const formatCell = (value: unknown): string => {
  if (value === null || value === undefined) return "-";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
};

const resultColumns = (columns: readonly string[]): TableColumns<Record<string, unknown>> =>
  columns.map((column) => ({
    key: column,
    title: column,
    render: (_value, record) => formatCell(record[column]),
  }));

/** 计划状态只有三种：OK / BLOCKED / 未做 EXPLAIN（只编译过）。 */
const planTone = (status: string | null) =>
  status === "BLOCKED" ? "danger" : status === "OK" ? "success" : "neutral";

const planLabel = (status: string | null) =>
  status === "BLOCKED" ? "已阻断" : status === "OK" ? "计划可执行" : "未估算成本";

/** 计划摘要：成本、估算行数、Join 路径与规范化 SQL —— dry-plan 与 dry-run 共用。 */
function PlanSummary({ plan }: { plan: PlanVO }) {
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2 text-xs text-[var(--muted-foreground)]">
        <Badge tone={planTone(plan.status)}>{planLabel(plan.status)}</Badge>
        <span>
          估算成本 <code className="font-mono">{plan.estimatedCost}</code>
        </span>
        <span>
          估算行数 <code className="font-mono">{plan.estimatedRows}</code>
        </span>
        <span>
          生效上限 <code className="font-mono">{plan.appliedLimit}</code>
        </span>
        {plan.verified ? <Badge tone="info">已受限执行校验</Badge> : null}
      </div>

      {plan.blockReason ? <Alert>{plan.blockReason}</Alert> : null}

      {plan.joins?.length ? (
        <Table
          columns={[
            { key: "relationId", title: "关系" },
            { key: "table", title: "引入表" },
            {
              key: "on",
              title: "连接条件",
              render: (_value, record) => `${record.leftColumn} = ${record.rightColumn}`,
            },
            { key: "cardinality", title: "基数" },
            { key: "direction", title: "方向" },
          ]}
          dataSource={plan.joins}
          size="small"
          emptyText="无跨表路径"
          pagination={false}
        />
      ) : null}

      <pre className="overflow-x-auto rounded-[var(--radius)] bg-[var(--accent)] p-3 text-xs text-[var(--foreground)]">
        {plan.sql}
      </pre>
      <div className="text-xs text-[var(--muted-foreground)]">
        <span className="font-medium">绑定值：</span>
        <code className="font-mono">{JSON.stringify(plan.bindValues)}</code>
      </div>

      {plan.planJson ? (
        <details className="text-xs text-[var(--muted-foreground)]">
          <summary className="cursor-pointer">EXPLAIN 原始计划</summary>
          <pre className="mt-1 overflow-x-auto rounded-[var(--radius)] bg-[var(--accent)] p-3 text-[11px] text-[var(--foreground)]">
            {plan.planJson}
          </pre>
        </details>
      ) : null}
    </div>
  );
}

export function DslPanel() {
  const manifestQuery = useQuery({
    queryKey: manifestQueryKeys.current,
    queryFn: ({ signal }) => getCurrentManifest(signal),
  });
  const [draft, setDraft] = useState<DslDraft>({
    manifestId: "",
    metricId: "metric.gmv",
    dimensions: "dim.region",
    timeExpression: "",
    timeField: "dim.order_date",
    limit: 100,
  });

  const manifestId = draft.manifestId || manifestQuery.data?.manifestId || "";
  const dsl = buildQueryDsl({ ...draft, manifestId });

  const compile = useMutation({ mutationFn: compileQuery });
  const dryPlan = useMutation({ mutationFn: dryPlanQuery });
  const dryRun = useMutation({ mutationFn: dryRunQuery });
  const execute = useMutation({ mutationFn: executeQuery });
  const busy = compile.isPending || dryPlan.isPending || dryRun.isPending || execute.isPending;

  const patch = (key: keyof DslDraft, value: string | number) =>
    setDraft((previous) => ({ ...previous, [key]: value }));

  const resetAll = () => {
    compile.reset();
    dryPlan.reset();
    dryRun.reset();
    execute.reset();
  };

  const plan: DryRunVO | PlanVO | undefined = dryRun.data ?? dryPlan.data;

  return (
    <Card>
      <CardHeader
        title="确定性链路调试"
        description="DSL 只引用资产 ID；编译、dry-plan、dry-run、执行共用同一编译产物"
      />
      <div className="space-y-3 p-4">
        <div className="space-y-2.5">
          <Field className={HORIZONTAL_FIELD_CLASS}>
            <FieldLabel htmlFor="dsl-manifest-id">清单 ID</FieldLabel>
            <Input
              id="dsl-manifest-id"
              size="small"
              variant="outlined"
              className="font-mono text-xs"
              value={manifestId}
              placeholder={manifestQuery.isPending ? "加载中…" : "发布清单 ID"}
              onChange={(event) => patch("manifestId", event.target.value)}
            />
          </Field>
          <Field className={HORIZONTAL_FIELD_CLASS}>
            <FieldLabel htmlFor="dsl-metric-id">指标 ID</FieldLabel>
            <Input
              id="dsl-metric-id"
              size="small"
              variant="outlined"
              className="font-mono text-xs"
              value={draft.metricId}
              onChange={(event) => patch("metricId", event.target.value)}
            />
          </Field>
          <Field className={HORIZONTAL_FIELD_CLASS}>
            <FieldLabel htmlFor="dsl-dimensions">维度 ID</FieldLabel>
            <Input
              id="dsl-dimensions"
              size="small"
              variant="outlined"
              className="font-mono text-xs"
              value={draft.dimensions}
              placeholder="逗号分隔"
              onChange={(event) => patch("dimensions", event.target.value)}
            />
          </Field>
          <Field className={HORIZONTAL_FIELD_CLASS}>
            <FieldLabel htmlFor="dsl-time-expression">相对时间</FieldLabel>
            <Input
              id="dsl-time-expression"
              size="small"
              variant="outlined"
              className="font-mono text-xs"
              value={draft.timeExpression}
              placeholder="如「近 30 天」；留空表示全量"
              onChange={(event) => patch("timeExpression", event.target.value)}
            />
          </Field>
          <Field className={HORIZONTAL_FIELD_CLASS}>
            <FieldLabel htmlFor="dsl-time-field">时间维度 ID</FieldLabel>
            <Input
              id="dsl-time-field"
              size="small"
              variant="outlined"
              className="font-mono text-xs"
              value={draft.timeField}
              placeholder="如 dim.order_date"
              onChange={(event) => patch("timeField", event.target.value)}
            />
          </Field>
          <Field className={HORIZONTAL_FIELD_CLASS}>
            <FieldLabel htmlFor="dsl-limit">行数上限</FieldLabel>
            <Input
              id="dsl-limit"
              size="small"
              variant="outlined"
              type="number"
              value={draft.limit}
              onChange={(event) => patch("limit", Number(event.target.value) || 1)}
            />
          </Field>
        </div>

        <div className="flex flex-wrap gap-2">
          <Button
            size="small"
            disabled={busy || !manifestId}
            onClick={() => {
              resetAll();
              compile.mutate(dsl);
            }}
          >
            编译
          </Button>
          <Button
            size="small"
            disabled={busy || !manifestId}
            onClick={() => {
              resetAll();
              dryPlan.mutate(dsl);
            }}
          >
            dry-plan（成本）
          </Button>
          <Button
            size="small"
            disabled={busy || !manifestId}
            onClick={() => {
              resetAll();
              dryRun.mutate(dsl);
            }}
          >
            dry-run（受限执行）
          </Button>
          <Button
            size="small"
            variant="primary"
            disabled={busy || !manifestId}
            onClick={() => {
              resetAll();
              execute.mutate(dsl);
            }}
          >
            编译并执行
          </Button>
        </div>

        {manifestQuery.isError ? (
          <Alert>{failureMessage(manifestQuery.error, "发布清单未加载，可手动填写清单 ID")}</Alert>
        ) : null}
        {compile.isError ? <Alert>{failureMessage(compile.error, "编译失败")}</Alert> : null}
        {dryPlan.isError ? <Alert>{failureMessage(dryPlan.error, "dry-plan 失败")}</Alert> : null}
        {dryRun.isError ? <Alert>{failureMessage(dryRun.error, "dry-run 被拒绝")}</Alert> : null}
        {execute.isError ? <Alert>{failureMessage(execute.error, "执行失败")}</Alert> : null}

        {compile.data ? (
          <div className="space-y-2">
            <pre className="overflow-x-auto rounded-[var(--radius)] bg-[var(--accent)] p-3 text-xs text-[var(--foreground)]">
              {compile.data.sql}
            </pre>
            <div className="text-xs text-[var(--muted-foreground)]">
              <span className="font-medium">绑定值：</span>
              <code className="font-mono">{JSON.stringify(compile.data.bindValues)}</code>
            </div>
          </div>
        ) : null}

        {plan ? <PlanSummary plan={plan} /> : null}

        {dryRun.data ? (
          <div className="space-y-2">
            <div className="text-xs text-[var(--muted-foreground)]">
              {dryRun.data.rowCount} 行 · {dryRun.data.elapsedMs} ms
              {dryRun.data.truncated ? " · 已达 dry-run 行数上限" : ""}
            </div>
            <Table
              columns={resultColumns(dryRun.data.columns)}
              dataSource={dryRun.data.rows}
              size="medium"
              emptyText="无结果"
              pagination={false}
            />
          </div>
        ) : null}

        {execute.data ? (
          <div className="space-y-2">
            <div className="text-xs text-[var(--muted-foreground)]">
              {execute.data.rowCount} 行 · {execute.data.elapsedMs} ms
              {execute.data.truncated ? " · 已截断" : ""}
              {execute.data.cached ? " · 来自结果缓存" : ""}
            </div>
            <Table
              columns={resultColumns(execute.data.columns)}
              dataSource={execute.data.rows}
              size="medium"
              emptyText="无结果"
              pagination={false}
            />
          </div>
        ) : null}
      </div>
    </Card>
  );
}
