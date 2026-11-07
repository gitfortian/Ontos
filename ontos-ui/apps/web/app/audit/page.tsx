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

import { listAuditLogs } from "@/service/audit";
import type { AuditLogVO } from "@/service/audit";
import { failureMessage } from "@/utils/error-message";

const ACTION_OPTIONS = [
  { value: "", label: "全部动作" },
  { value: "COMPILE", label: "COMPILE" },
  { value: "EXECUTE", label: "EXECUTE" },
  { value: "REJECT", label: "REJECT" },
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
