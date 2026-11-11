import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Badge,
  Button,
  Field,
  FieldLabel,
  PageHeader,
  SectionCard,
  Select,
  SelectContent,
  SelectItem,
  SelectItemIndicator,
  SelectItemText,
  SelectTrigger,
  SelectValue,
  Table,
  Textarea,
  toast,
  type BadgeProps,
  type TableColumns,
} from "@ontos/ui";
import { useSearchParams } from "react-router-dom";

import {
  classifyFailureSample,
  getFailureDashboard,
  getFailureSampleDetail,
  listAuditLogs,
  listFailureCauseCategories,
  returnFailureSampleToRegression,
  transitionFailureSample,
} from "@/service/audit";
import type {
  AuditLogVO,
  FailureRegressionTarget,
  FailureSampleStatus,
  FailureSampleVO,
} from "@/service/audit";
import { failureMessage } from "@/utils/error-message";

import { STATUS_LABEL, buildFailureTimeline, nextStatusActions } from "./failure-lifecycle";

const ACTION_OPTIONS = [
  { value: "", label: "全部动作" },
  { value: "COMPILE", label: "COMPILE" },
  { value: "EXECUTE", label: "EXECUTE" },
  { value: "REJECT", label: "REJECT" },
];

/** 失败看板数据源题集（setId 与 runner 写入一致：test 集升版后为 test-v5）。 */
const FAILURE_SET_OPTIONS = [
  { value: "test-v5", label: "test-v5（主指标）" },
  { value: "smoke-v3", label: "smoke-v3（冒烟回归）" },
];

const DIMENSION_TITLES: Record<string, string> = {
  scenario_entry_mode: "场景入口",
  query_shape: "查询形状",
  turn_type: "轮次类型",
  is_composite: "复合意图",
  risk_type: "风险类型",
};

const DIMENSION_ORDER = [
  "scenario_entry_mode",
  "query_shape",
  "turn_type",
  "is_composite",
  "risk_type",
];

const STATUS_TONE: Record<FailureSampleStatus, NonNullable<BadgeProps["tone"]>> = {
  open: "warning",
  triaged: "info",
  fixed: "success",
  regressed: "neutral",
  ignored: "neutral",
};

/** 回流目标：test 集冻结只能升版，UI 不提供 test 选项（后端同样拒绝）。 */
const REGRESSION_TARGET_OPTIONS: Array<{ value: FailureRegressionTarget; label: string }> = [
  { value: "dev", label: "dev（开发回归集）" },
  { value: "validation", label: "validation（验证集）" },
];

const failureDashboardQueryKey = ["failure-dashboard"] as const;
const failureCategoryQueryKey = ["failure-categories"] as const;

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-2 text-xs">
      <span className="w-24 shrink-0 text-[var(--muted-foreground)]">{label}</span>
      <span className="min-w-0 break-all font-mono text-[var(--foreground)]">{value}</span>
    </div>
  );
}

/** 样本详情卡片（T4.3）：状态流转 / 根因分类 / 退回回归集 + 事件时间线。 */
function FailureDetailPanel({ sample, onClose }: { sample: FailureSampleVO; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [category, setCategory] = useState("");
  const [targetSet, setTargetSet] = useState<FailureRegressionTarget>("dev");
  const [note, setNote] = useState("");

  const detail = useQuery({
    queryKey: ["failure-sample", sample.id],
    queryFn: ({ signal }) => getFailureSampleDetail(sample.id, signal),
  });
  const categories = useQuery({
    queryKey: failureCategoryQueryKey,
    queryFn: ({ signal }) => listFailureCauseCategories(signal),
    staleTime: Number.POSITIVE_INFINITY,
  });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ["failure-sample", sample.id] });
    void queryClient.invalidateQueries({ queryKey: failureDashboardQueryKey });
  };
  const trimmedNote = () => note.trim() || undefined;

  const transition = useMutation({
    mutationFn: (toStatus: FailureSampleStatus) =>
      transitionFailureSample(sample.id, toStatus, trimmedNote()),
    onSuccess: (result) => {
      toast.success(`状态已流转为 ${STATUS_LABEL[result.status] ?? result.status}`);
      setNote("");
      refresh();
    },
    onError: (error) => toast.error(failureMessage(error, "状态流转失败")),
  });

  const classify = useMutation({
    mutationFn: () => classifyFailureSample(sample.id, category, trimmedNote()),
    onSuccess: (result) => {
      toast.success(`已归类为 ${result.category ?? "-"}`);
      setNote("");
      refresh();
    },
    onError: (error) => toast.error(failureMessage(error, "根因分类失败")),
  });

  const regression = useMutation({
    mutationFn: () => returnFailureSampleToRegression(sample.id, targetSet, trimmedNote()),
    onSuccess: (result) => {
      toast.success(`已退回 ${result.targetSet}：${result.regressionSampleId ?? "-"}`);
      setNote("");
      refresh();
    },
    onError: (error) => toast.error(failureMessage(error, "退回回归集失败")),
  });

  const data = detail.data;
  const status: FailureSampleStatus = data?.status ?? "open";
  const categoryLabel = (code?: string | null) => {
    if (!code) {
      return "未分类";
    }
    return categories.data?.find((item) => item.code === code)?.label ?? code;
  };
  const timeline = buildFailureTimeline(data?.statusEvents, data?.classifications, categoryLabel);

  const busy = transition.isPending || classify.isPending || regression.isPending;

  return (
    <SectionCard
      className="mt-4"
      title={
        <span className="flex flex-wrap items-center gap-2">
          <span>样本 #{sample.id}</span>
          <span className="font-mono text-xs text-[var(--muted-foreground)]">
            {sample.questionId}
          </span>
          <Badge tone={STATUS_TONE[status]}>{STATUS_LABEL[status]}</Badge>
          <span className="text-xs text-[var(--muted-foreground)]">
            根因：{categoryLabel(data?.category)}
          </span>
        </span>
      }
    >
      {detail.isError ? (
        <Alert className="mb-3">{failureMessage(detail.error, "样本详情加载失败")}</Alert>
      ) : null}
      <div className="grid gap-4 lg:grid-cols-2 max-lg:grid-cols-1">
        <div className="space-y-1.5">
          <InfoRow label="题集 / run" value={`${sample.setId} · ${sample.runId}`} />
          <InfoRow label="场景入口" value={sample.scenarioEntryMode} />
          <InfoRow label="查询形状" value={sample.queryShape} />
          <InfoRow label="轮次" value={sample.turnType} />
          <InfoRow label="复合意图" value={sample.isComposite ? "是" : "否"} />
          <InfoRow label="风险类型" value={sample.riskType} />
          <InfoRow
            label="终态（期望→实际）"
            value={`${sample.expectedTerminal ?? "-"} → ${sample.actualTerminal ?? "-"}`}
          />
          <InfoRow label="错误码" value={sample.errorCode ? String(sample.errorCode) : "-"} />
          <InfoRow label="失败原因" value={sample.reason ?? "-"} />
          <InfoRow label="登记时间" value={sample.createdAt ?? "-"} />
        </div>
        <div className="space-y-3">
          <Field>
            <FieldLabel htmlFor={`failure-note-${sample.id}`}>
              处置备注（写入事件，可选）
            </FieldLabel>
            <Textarea
              id={`failure-note-${sample.id}`}
              rows={2}
              value={note}
              onValueChange={setNote}
              placeholder="例如：fix-471e4f3 补充订单项销量指标"
            />
          </Field>
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs text-[var(--muted-foreground)]">状态流转</span>
            {nextStatusActions(status).map((action) => (
              <Button
                key={action.to}
                size="small"
                variant={action.to === "fixed" ? "primary" : "secondary"}
                loading={transition.isPending}
                disabled={busy}
                onClick={() => transition.mutate(action.to)}
              >
                {action.label}
              </Button>
            ))}
          </div>
          <div className="flex flex-wrap items-end gap-2">
            <Field className="w-64">
              <FieldLabel>根因分类</FieldLabel>
              <Select
                size="small"
                items={(categories.data ?? []).map((item) => ({
                  value: item.code,
                  label: item.label,
                }))}
                value={category}
                onValueChange={(value) => setCategory(value ?? "")}
              >
                <SelectTrigger variant="outlined" aria-label="根因分类">
                  <SelectValue placeholder="选择根因分类" />
                </SelectTrigger>
                <SelectContent>
                  {(categories.data ?? []).map((option) => (
                    <SelectItem key={option.code} value={option.code}>
                      <SelectItemText>{option.label}</SelectItemText>
                      <SelectItemIndicator />
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Button
              size="small"
              loading={classify.isPending}
              disabled={busy || !category}
              onClick={() => classify.mutate()}
            >
              保存分类
            </Button>
          </div>
          <div className="flex flex-wrap items-end gap-2">
            <Field className="w-52">
              <FieldLabel>退回回归集（仅 fixed 可用）</FieldLabel>
              <Select
                size="small"
                items={REGRESSION_TARGET_OPTIONS}
                value={targetSet}
                onValueChange={(value) =>
                  setTargetSet(value === "validation" ? "validation" : "dev")
                }
              >
                <SelectTrigger variant="outlined" aria-label="回流目标题集">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {REGRESSION_TARGET_OPTIONS.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      <SelectItemText>{option.label}</SelectItemText>
                      <SelectItemIndicator />
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Button
              size="small"
              loading={regression.isPending}
              disabled={busy || status !== "fixed"}
              onClick={() => regression.mutate()}
            >
              退回回归集
            </Button>
            <Button size="small" variant="ghost" onClick={onClose}>
              收起
            </Button>
          </div>
        </div>
      </div>
      <div className="mt-4 border-t border-[var(--border)] pt-3">
        <div className="mb-2 text-xs font-medium text-[var(--foreground)]">
          事件时间线（只追加）
        </div>
        {timeline.length === 0 ? (
          <div className="text-xs text-[var(--muted-foreground)]">
            暂无事件：样本登记即进入「待分诊」，尚未流转或分类。
          </div>
        ) : (
          <ol className="space-y-1.5">
            {timeline.map((event) => (
              <li key={event.key} className="flex items-start gap-2 text-xs">
                <Badge tone={event.kind === "状态" ? "info" : "warning"}>{event.kind}</Badge>
                <span className="font-mono text-[var(--foreground)]">{event.text}</span>
                <span className="text-[var(--muted-foreground)]">by {event.actor}</span>
                {event.note ? (
                  <span className="text-[var(--muted-foreground)]">· {event.note}</span>
                ) : null}
                <span className="ml-auto shrink-0 font-mono text-[var(--muted-foreground)]">
                  {event.at}
                </span>
              </li>
            ))}
          </ol>
        )}
      </div>
    </SectionCard>
  );
}

const auditQueryKey = ["audit-logs"] as const;

/** 失败看板（G-5 五维下钻 + 单维联动）：维度取值可点击收窄样本，样本可打开处置卡片。 */
function FailureDashboard() {
  // M6 全量深度审核 D-17：题集可切换（原固定 test-v3，smoke-v3 选项定义却无 UI）
  const [setId, setSetId] = useState(FAILURE_SET_OPTIONS[0].value);
  const [drill, setDrill] = useState<{ dimension: string; value: string } | null>(null);
  const [selected, setSelected] = useState<FailureSampleVO | null>(null);

  const query = useQuery({
    queryKey: [...failureDashboardQueryKey, setId, drill?.dimension ?? "", drill?.value ?? ""],
    queryFn: ({ signal }) =>
      getFailureDashboard(
        {
          setId,
          recentLimit: 50,
          drillDimension: drill?.dimension,
          drillValue: drill?.value,
        },
        signal,
      ),
  });

  const data = query.data;
  const drillActive = Boolean(data?.drillDimension);

  const toggleDrill = (dimension: string, value: string) => {
    setDrill((current) =>
      current && current.dimension === dimension && current.value === value
        ? null
        : { dimension, value },
    );
  };

  const columns: TableColumns<FailureSampleVO> = [
    {
      key: "questionId",
      title: "题目",
      minWidth: 180,
      render: (_value, record) => <span className="font-mono text-xs">{record.questionId}</span>,
    },
    { key: "scenarioEntryMode", title: "入口", width: 100 },
    { key: "queryShape", title: "形状", width: 120 },
    { key: "turnType", title: "轮次", width: 110 },
    {
      key: "isComposite",
      title: "复合",
      width: 80,
      render: (_value, record) => (
        <Badge tone={record.isComposite ? "warning" : "neutral"}>
          {record.isComposite ? "是" : "否"}
        </Badge>
      ),
    },
    {
      key: "riskType",
      title: "风险",
      width: 120,
      render: (_value, record) => <Badge tone="warning">{record.riskType}</Badge>,
    },
    {
      key: "errorCode",
      title: "错误码",
      width: 110,
      render: (_value, record) => (
        <span className="font-mono text-xs">{record.errorCode ?? "-"}</span>
      ),
    },
    {
      key: "reason",
      title: "原因",
      minWidth: 200,
      render: (_value, record) => (
        <span className="text-xs text-[var(--muted-foreground)]">{record.reason ?? "-"}</span>
      ),
    },
    { key: "createdAt", title: "时间", width: 180 },
    {
      key: "actions",
      title: "操作",
      width: 100,
      render: (_value, record) => (
        <Button
          size="small"
          variant="ghost"
          onClick={() => setSelected(record)}
          aria-label={`处置样本 ${record.questionId}`}
        >
          处置
        </Button>
      ),
    },
  ];

  return (
    <section className="mb-6">
      <PageHeader
        title="失败看板"
        description={`评测失败样本五维下钻（G-5）· 题集 ${setId} · 共 ${data?.total ?? 0} 条失败样本${
          drillActive ? ` · 联动命中 ${data?.filteredTotal ?? 0} 条` : ""
        }；纠正样本经修复后按题集升版进回归集`}
        extra={
          <div className="flex items-center gap-2">
            <Select
              size="small"
              items={FAILURE_SET_OPTIONS}
              value={setId}
              onValueChange={(value) => {
                setSetId(value ?? FAILURE_SET_OPTIONS[0].value);
                setDrill(null);
                setSelected(null);
              }}
            >
              <SelectTrigger variant="outlined" className="w-44" aria-label="评测题集">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {FAILURE_SET_OPTIONS.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    <SelectItemText>{option.label}</SelectItemText>
                    <SelectItemIndicator />
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {drillActive ? (
              <Button size="small" variant="ghost" onClick={() => setDrill(null)}>
                清除联动：{DIMENSION_TITLES[data?.drillDimension ?? ""] ?? data?.drillDimension}=
                {data?.drillValue}
              </Button>
            ) : null}
            <Button size="small" onClick={() => void query.refetch()} loading={query.isFetching}>
              刷新
            </Button>
          </div>
        }
      />
      {query.isError ? (
        <Alert className="mb-3">{failureMessage(query.error, "失败看板加载失败")}</Alert>
      ) : null}
      {data && data.total === 0 ? (
        <Alert className="mb-3">
          看板空态：当前题集无失败样本。失败样本由评测 runner 在出现失败时自动登记。
        </Alert>
      ) : null}
      <div className="mb-4 grid grid-cols-2 gap-3 max-md:grid-cols-1 lg:grid-cols-5">
        {DIMENSION_ORDER.map((dimension) => {
          const rows = data?.dimensions?.[dimension] ?? [];
          return (
            <div key={dimension} className="rounded-lg border border-[var(--border)] p-3">
              <div className="mb-2 text-xs font-medium text-[var(--muted-foreground)]">
                {DIMENSION_TITLES[dimension] ?? dimension}
              </div>
              {rows.length === 0 ? (
                <div className="text-xs text-[var(--muted-foreground)]">无失败</div>
              ) : (
                rows.slice(0, 5).map((row) => {
                  const active =
                    data?.drillDimension === dimension && data?.drillValue === String(row.value);
                  return (
                    <button
                      key={String(row.value)}
                      type="button"
                      onClick={() => toggleDrill(dimension, String(row.value))}
                      aria-pressed={active}
                      className={`flex w-full cursor-pointer items-center justify-between rounded px-1 text-xs hover:bg-[var(--accent)] ${
                        active
                          ? "bg-[var(--primary-subtle)] font-medium text-[var(--primary)]"
                          : "text-[var(--foreground)]"
                      }`}
                    >
                      <span className="truncate font-mono">{String(row.value)}</span>
                      <span>{row.count}</span>
                    </button>
                  );
                })
              )}
            </div>
          );
        })}
      </div>
      {data && data.total > 0 ? (
        <Table
          columns={columns}
          dataSource={data.recentSamples ?? []}
          rowKey="id"
          size="small"
          loading={query.isPending}
          emptyText={drillActive ? "该联动条件下无失败样本" : "暂无失败样本"}
          pagination={false}
          scroll={{ x: 1200 }}
        />
      ) : null}
      {selected ? <FailureDetailPanel sample={selected} onClose={() => setSelected(null)} /> : null}
    </section>
  );
}

const columns: TableColumns<AuditLogVO> = [
  { key: "createdAt", title: "时间", width: 200 },
  {
    key: "action",
    title: "动作",
    width: 110,
    render: (_value, record) => <Badge tone="info">{record.action}</Badge>,
  },
  {
    key: "metricId",
    title: "指标",
    minWidth: 160,
    render: (_value, record) => <span className="font-mono text-xs">{record.metricId ?? "-"}</span>,
  },
  { key: "rowCount", title: "行数", width: 90, align: "right" },
  {
    key: "requestId",
    title: "请求 ID",
    minWidth: 180,
    render: (_value, record) => <span className="font-mono text-xs">{record.requestId}</span>,
  },
  {
    key: "manifestId",
    title: "清单 ID",
    minWidth: 180,
    render: (_value, record) => (
      <span className="font-mono text-xs">{record.manifestId ?? "-"}</span>
    ),
  },
  {
    key: "traceId",
    title: "Trace",
    minWidth: 180,
    render: (_value, record) => <span className="font-mono text-xs">{record.traceId ?? "-"}</span>,
  },
];

export function AuditPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const action = searchParams.get("action") ?? "";
  const pageNo = Math.max(1, Number(searchParams.get("pageNo") ?? "1") || 1);

  const query = useQuery({
    queryKey: [...auditQueryKey, action, pageNo],
    queryFn: ({ signal }) => listAuditLogs(action, pageNo, signal),
  });

  const updateParams = (next: { action?: string; pageNo?: number }) => {
    const params = new URLSearchParams(searchParams);
    if (next.action !== undefined) {
      if (next.action) params.set("action", next.action);
      else params.delete("action");
      params.set("pageNo", "1");
    }
    if (next.pageNo !== undefined) params.set("pageNo", String(next.pageNo));
    setSearchParams(params);
  };

  const total = query.data?.total ?? 0;

  return (
    <div className="px-6 py-5 max-md:px-4">
      <FailureDashboard />
      <PageHeader
        title="审计日志"
        description="只追加、按租户隔离；记录 DSL、编译 SQL 与结果行数"
        extra={
          <div className="flex items-center gap-2">
            <Select
              size="small"
              items={ACTION_OPTIONS}
              value={action}
              onValueChange={(value) => updateParams({ action: value ?? "" })}
            >
              <SelectTrigger variant="outlined" className="w-36" aria-label="动作筛选">
                <SelectValue placeholder="全部动作" />
              </SelectTrigger>
              <SelectContent>
                {ACTION_OPTIONS.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    <SelectItemText>{option.label}</SelectItemText>
                    <SelectItemIndicator />
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button size="small" onClick={() => void query.refetch()} loading={query.isFetching}>
              刷新
            </Button>
          </div>
        }
      />

      {query.isError ? (
        <Alert className="mb-4">{failureMessage(query.error, "审计日志加载失败")}</Alert>
      ) : null}

      <Table
        columns={columns}
        dataSource={query.data?.list ?? []}
        rowKey="id"
        size="medium"
        loading={query.isPending}
        emptyText="暂无审计记录"
        pagination={false}
        scroll={{ x: 1000 }}
      />

      <div className="mt-4 flex items-center justify-end gap-3 text-xs text-[var(--muted-foreground)]">
        共 {total} 条 · 第 {pageNo} 页
        <Button
          size="small"
          disabled={pageNo <= 1}
          onClick={() => updateParams({ pageNo: pageNo - 1 })}
        >
          上一页
        </Button>
        <Button
          size="small"
          disabled={pageNo * 20 >= total}
          onClick={() => updateParams({ pageNo: pageNo + 1 })}
        >
          下一页
        </Button>
      </div>
    </div>
  );
}
