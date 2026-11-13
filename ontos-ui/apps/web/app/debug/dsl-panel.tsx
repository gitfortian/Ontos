import { useMemo, useState } from "react";
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

import { BizError } from "@/service/query";
import { getCurrentManifest, manifestQueryKeys } from "@/service/ontology";
import {
  buildQueryDsl,
  compileQuery,
  dryPlanQuery,
  dryRunQuery,
  executeQueryWithMeta,
  getCopyTemplate,
  replayPlan,
} from "@/service/query";
import type { DslDraft, DryRunVO, PlanVO } from "@/service/query";
import { formatCellValue } from "@/utils/cell-format";
import { parseCaliberCandidates } from "@/utils/caliber-candidates";
import { failureMessage } from "@/utils/error-message";

const HORIZONTAL_FIELD_CLASS = "grid grid-cols-[104px_minmax(0,1fr)] items-start gap-2";

/** 口径变体澄清的业务错误码（M3/W2 CALIBER_AMBIGUOUS）。 */
const CALIBER_AMBIGUOUS_CODE = 1002000033;

const resultColumns = (columns: readonly string[]): TableColumns<Record<string, unknown>> =>
  columns.map((column) => ({
    key: column,
    title: column,
    render: (_value, record) => formatCellValue(record[column]),
  }));

/** 计划状态只有三种：OK / BLOCKED / 未做 EXPLAIN（只编译过）。 */
const planTone = (status: string | null) =>
  status === "BLOCKED" ? "danger" : status === "OK" ? "success" : "neutral";

const planLabel = (status: string | null) =>
  status === "BLOCKED" ? "已阻断" : status === "OK" ? "计划可执行" : "未估算成本";

/** 计划摘要：成本、估算行数、Join 路径与规范化 SQL —— dry-plan / dry-run / 回放共用。 */
function PlanSummary({ plan }: { plan: PlanVO }) {
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2 text-xs text-[var(--muted-foreground)]">
        <Badge tone={planTone(plan.status)}>{planLabel(plan.status)}</Badge>
        {plan.estimatedCost !== null && plan.estimatedCost !== undefined ? (
          <span>
            估算成本 <code className="font-mono">{plan.estimatedCost}</code>
          </span>
        ) : null}
        {plan.estimatedRows !== null && plan.estimatedRows !== undefined ? (
          <span>
            估算行数 <code className="font-mono">{plan.estimatedRows}</code>
          </span>
        ) : null}
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

/** 口径澄清卡片（T5.2）：歧义必须回到人，候选展示后由用户单选回填，不允许默认猜测。 */
function CaliberClarification({
  message,
  onPick,
}: {
  message: string;
  onPick: (variantId: string) => void;
}) {
  const candidates = useMemo(() => parseCaliberCandidates(message), [message]);
  const aliases = candidates.map((candidate) => candidate.alias || candidate.variantId).join(" / ");

  // 文案来自清单模板服务（M3/W4）：占位符由服务端渲染，前端不拼字符串。
  const copyQuery = useQuery({
    queryKey: ["copy-template", "CALIBER_AMBIGUOUS", aliases],
    queryFn: () => getCopyTemplate("CALIBER_AMBIGUOUS", { variant_aliases: aliases }),
    enabled: candidates.length > 0,
  });

  if (candidates.length === 0) {
    return <Alert>{message}</Alert>;
  }

  return (
    <div className="space-y-2 rounded-[var(--radius)] border border-[var(--border)] p-3">
      <p className="text-xs text-[var(--foreground)]">
        {copyQuery.data?.text ?? "该指标存在多个口径，请选择其中一个。"}
        {copyQuery.data?.fallback ? (
          <span className="ml-1 text-[var(--muted-foreground)]">（通用文案）</span>
        ) : null}
      </p>
      <div className="flex flex-wrap gap-2">
        {candidates.map((candidate) => (
          <Button
            key={candidate.variantId}
            size="small"
            variant="primary"
            onClick={() => onPick(candidate.variantId)}
          >
            {candidate.alias || candidate.variantId}
            <span className="ml-1 font-mono text-[11px] opacity-70">{candidate.variantId}</span>
          </Button>
        ))}
      </div>
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
    caliberVariantId: "",
    limit: 100,
  });

  const manifestId = draft.manifestId || manifestQuery.data?.manifestId || "";
  const dsl = buildQueryDsl({ ...draft, manifestId });

  const compile = useMutation({ mutationFn: compileQuery });
  const dryPlan = useMutation({ mutationFn: dryPlanQuery });
  const dryRun = useMutation({ mutationFn: dryRunQuery });
  // execute 用 withMeta：请求 ID 是回放 /plan/{requestId} 的唯一锚点
  const execute = useMutation({ mutationFn: executeQueryWithMeta });
  const replay = useMutation({ mutationFn: replayPlan });
  const busy =
    compile.isPending ||
    dryPlan.isPending ||
    dryRun.isPending ||
    execute.isPending ||
    replay.isPending;

  const patch = (key: keyof DslDraft, value: string | number) =>
    setDraft((previous) => ({ ...previous, [key]: value }));

  const resetAll = () => {
    compile.reset();
    dryPlan.reset();
    dryRun.reset();
    execute.reset();
    replay.reset();
  };

  const runExecute = (nextDraft: DslDraft) => {
    resetAll();
    execute.mutate(buildQueryDsl({ ...nextDraft, manifestId }));
  };

  const plan: DryRunVO | PlanVO | undefined = dryRun.data ?? dryPlan.data ?? replay.data;
  const executedRequestId = execute.data?.requestId ?? null;

  // 口径澄清只在 CALIBER_AMBIGUOUS 时出现；其他错误保持原样展示。
  const caliberMessage =
    execute.error instanceof BizError && execute.error.code === CALIBER_AMBIGUOUS_CODE
      ? execute.error.message
      : null;

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
              placeholder="多口径指标（如 metric.paid_gmv）需先澄清口径"
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
              placeholder="如「近 30 天」「上季度」；留空表示全量"
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
            <FieldLabel htmlFor="dsl-caliber-variant">口径变体 ID</FieldLabel>
            <Input
              id="dsl-caliber-variant"
              size="small"
              variant="outlined"
              className="font-mono text-xs"
              value={draft.caliberVariantId}
              placeholder="澄清卡片回填，或直接填写"
              onChange={(event) => patch("caliberVariantId", event.target.value)}
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
            onClick={() => runExecute(draft)}
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
        {caliberMessage ? (
          <CaliberClarification
            message={caliberMessage}
            onPick={(variantId) => runExecute({ ...draft, caliberVariantId: variantId })}
          />
        ) : null}
        {execute.isError && !caliberMessage ? (
          <Alert>{failureMessage(execute.error, "执行失败")}</Alert>
        ) : null}
        {replay.isError ? <Alert>{failureMessage(replay.error, "回放失败")}</Alert> : null}

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
              {execute.data.data.rowCount} 行 · {execute.data.data.elapsedMs} ms
              {execute.data.data.truncated ? " · 已截断" : ""}
              {execute.data.data.cached ? " · 来自结果缓存" : ""}
            </div>
            {executedRequestId ? (
              <div className="flex flex-wrap items-center gap-2 text-xs text-[var(--muted-foreground)]">
                <span>
                  请求 ID <code className="font-mono">{executedRequestId}</code>
                </span>
                <Button
                  size="small"
                  disabled={busy}
                  onClick={() => {
                    replay.reset();
                    replay.mutate(executedRequestId);
                  }}
                >
                  回放编译产物
                </Button>
              </div>
            ) : null}
            <Table
              columns={resultColumns(execute.data.data.columns)}
              dataSource={execute.data.data.rows}
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
