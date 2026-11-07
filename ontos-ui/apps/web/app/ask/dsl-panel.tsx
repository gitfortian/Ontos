import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  Alert,
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
import { buildQueryDsl, compileQuery, executeQuery } from "@/service/query";
import type { DslDraft, ExecuteVO } from "@/service/query";
import { failureMessage } from "@/utils/error-message";

const HORIZONTAL_FIELD_CLASS = "grid grid-cols-[104px_minmax(0,1fr)] items-start gap-2";

const formatCell = (value: unknown): string => {
  if (value === null || value === undefined) return "-";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
};

const resultColumns = (result: ExecuteVO): TableColumns<Record<string, unknown>> =>
  result.columns.map((column) => ({
    key: column,
    title: column,
    render: (_value, record) => formatCell(record[column]),
  }));

export function DslPanel() {
  const manifestQuery = useQuery({
    queryKey: manifestQueryKeys.current,
    queryFn: ({ signal }) => getCurrentManifest(signal),
  });
  const [draft, setDraft] = useState<DslDraft>({
    manifestId: "",
    metricId: "metric.gmv",
    dimensions: "dim.region",
    limit: 100,
  });

  const manifestId = draft.manifestId || manifestQuery.data?.manifestId || "";
  const dsl = buildQueryDsl({ ...draft, manifestId });

  const compile = useMutation({ mutationFn: compileQuery });
  const execute = useMutation({ mutationFn: executeQuery });
  const busy = compile.isPending || execute.isPending;

  const patch = (key: keyof DslDraft, value: string | number) =>
    setDraft((previous) => ({ ...previous, [key]: value }));

  return (
    <Card>
      <CardHeader
        title="确定性链路调试"
        description="DSL 只引用资产 ID；编译与执行共用同一 QueryService"
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

        <div className="flex gap-2">
          <Button
            size="small"
            onClick={() => {
              execute.reset();
              compile.mutate(dsl);
            }}
            disabled={busy || !manifestId}
          >
            编译（dry-plan）
          </Button>
          <Button
            size="small"
            variant="primary"
            onClick={() => {
              compile.reset();
              execute.mutate(dsl);
            }}
            disabled={busy || !manifestId}
          >
            编译并执行
          </Button>
        </div>

        {manifestQuery.isError ? (
          <Alert>{failureMessage(manifestQuery.error, "发布清单未加载，可手动填写清单 ID")}</Alert>
        ) : null}
        {compile.isError ? <Alert>{failureMessage(compile.error, "编译失败")}</Alert> : null}
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

        {execute.data ? (
          <div className="space-y-2">
            <div className="text-xs text-[var(--muted-foreground)]">
              {execute.data.rowCount} 行 · {execute.data.elapsedMs} ms
              {execute.data.truncated ? " · 已截断" : ""}
            </div>
            <Table
              columns={resultColumns(execute.data)}
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
