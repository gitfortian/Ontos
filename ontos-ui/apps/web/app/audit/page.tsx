import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Badge,
  Button,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectItemIndicator,
  SelectItemText,
  SelectTrigger,
  SelectValue,
  Table,
  type TableColumns,
} from "@ontos/ui";
import { useSearchParams } from "react-router-dom";

import { getFailureDashboard, listAuditLogs } from "@/service/audit";
import type { AuditLogVO, FailureDashboardVO } from "@/service/audit";
import { failureMessage } from "@/utils/error-message";

const ACTION_OPTIONS = [
  { value: "", label: "全部动作" },
  { value: "COMPILE", label: "COMPILE" },
  { value: "EXECUTE", label: "EXECUTE" },
  { value: "REJECT", label: "REJECT" },
];

/** 失败看板数据源题集（test 集 v3 为主分母；冒烟集随 smoke-runner 写入）。 */
const FAILURE_SET_OPTIONS = [
  { value: "test-v3", label: "test-v3（主指标）" },
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

const recentColumns: TableColumns<FailureDashboardVO["recentSamples"][number]> = [
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
];

const auditQueryKey = ["audit-logs"] as const;

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

/** 失败看板（G-5 五维下钻）：评测失败样本分类落库后的下钻视图。 */
function FailureDashboard() {
  const setId = FAILURE_SET_OPTIONS[0].value;
  const query = useQuery({
    queryKey: ["failure-dashboard", setId],
    queryFn: ({ signal }) => getFailureDashboard(setId, 50, signal),
  });

  const data = query.data;

  return (
    <section className="mb-6">
      <PageHeader
        title="失败看板"
        description={`评测失败样本五维下钻（G-5）· 题集 ${setId} · 共 ${data?.total ?? 0} 条失败样本；纠正样本经修复后按题集升版进回归集`}
        extra={
          <Button size="small" onClick={() => void query.refetch()} loading={query.isFetching}>
            刷新
          </Button>
        }
      />
      {query.isError ? (
        <Alert className="mb-3">{failureMessage(query.error, "失败看板加载失败")}</Alert>
      ) : null}
      {data && data.total === 0 ? (
        <Alert className="mb-3">
          看板空态：当前评测无失败样本（首轮收敛 269/0/2/0）。失败样本由评测 runner 自动登记。
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
                rows.slice(0, 5).map((row) => (
                  <div key={row.value} className="flex items-center justify-between text-xs">
                    <span className="font-mono">{row.value}</span>
                    <span>{row.count}</span>
                  </div>
                ))
              )}
            </div>
          );
        })}
      </div>
      {data && data.total > 0 ? (
        <Table
          columns={recentColumns}
          dataSource={data.recentSamples ?? []}
          rowKey="id"
          size="small"
          loading={query.isPending}
          emptyText="暂无失败样本"
          pagination={false}
          scroll={{ x: 1100 }}
        />
      ) : null}
    </section>
  );
}

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
