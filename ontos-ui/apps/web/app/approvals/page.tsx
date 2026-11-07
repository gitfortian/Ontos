import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
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
  toast,
  type BadgeProps,
  type TableColumns,
} from "@ontos/ui";
import { useSearchParams } from "react-router-dom";

import { decideApproval, listApprovals } from "@/service/approval";
import type { ApprovalDecision, ApprovalStatus, ApprovalVO } from "@/service/approval";
import { failureMessage } from "@/utils/error-message";

const STATUS_TONE: Record<ApprovalStatus, NonNullable<BadgeProps["tone"]>> = {
  PENDING: "warning",
  APPROVED: "success",
  REJECTED: "danger",
  EXPIRED: "neutral",
};

const STATUS_OPTIONS = [
  { value: "", label: "全部状态" },
  { value: "PENDING", label: "PENDING" },
  { value: "APPROVED", label: "APPROVED" },
  { value: "REJECTED", label: "REJECTED" },
  { value: "EXPIRED", label: "EXPIRED" },
];

const approvalQueryKey = ["approvals"] as const;

export function ApprovalsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const status = searchParams.get("status") ?? "PENDING";
  const pageNo = Math.max(1, Number(searchParams.get("pageNo") ?? "1") || 1);
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: [...approvalQueryKey, status, pageNo],
    queryFn: ({ signal }) => listApprovals(status, pageNo, signal),
  });

  const decision = useMutation({
    mutationFn: ({ id, value }: { id: string; value: ApprovalDecision }) =>
      decideApproval(id, value),
    onSuccess: () => {
      toast.success("审批已提交");
      void queryClient.invalidateQueries({ queryKey: approvalQueryKey });
    },
    onError: (error) => toast.error(failureMessage(error, "审批提交失败")),
  });

  const updateParams = (next: { status?: string; pageNo?: number }) => {
    const params = new URLSearchParams(searchParams);
    if (next.status !== undefined) {
      if (next.status) params.set("status", next.status);
      else params.delete("status");
      params.set("pageNo", "1");
    }
    if (next.pageNo !== undefined) params.set("pageNo", String(next.pageNo));
    setSearchParams(params);
  };

  const columns: TableColumns<ApprovalVO> = [
    {
      key: "kind",
      title: "类型",
      minWidth: 180,
      render: (_value, record) => <span className="font-mono text-xs">{record.kind}</span>,
    },
    { key: "userId", title: "发起人", width: 140 },
    {
      key: "status",
      title: "状态",
      width: 110,
      render: (_value, record) => <Badge tone={STATUS_TONE[record.status]}>{record.status}</Badge>,
    },
    { key: "requestedAt", title: "申请时间", width: 200 },
    { key: "reason", title: "理由", minWidth: 160 },
    {
      key: "actions",
      title: "操作",
      width: 160,
      render: (_value, record) =>
        record.status === "PENDING" ? (
          <div className="flex gap-2">
            <Button
              size="small"
              variant="primary"
              loading={decision.isPending}
              onClick={() => decision.mutate({ id: record.id, value: "APPROVED" })}
            >
              批准
            </Button>
            <Button
              size="small"
              variant="danger"
              loading={decision.isPending}
              onClick={() => decision.mutate({ id: record.id, value: "REJECTED" })}
            >
              拒绝
            </Button>
          </div>
        ) : (
          <span className="text-xs text-[var(--muted-foreground)]">已处理</span>
        ),
    },
  ];

  const records = query.data?.list ?? [];
  const total = query.data?.total ?? 0;

  return (
    <div className="px-6 py-5 max-md:px-4">
      <PageHeader
        title="审批中心"
        description="决策人取自认证上下文，仅 PENDING 单据可处理"
        extra={
          <div className="flex items-center gap-2">
            <Select
              size="small"
              items={STATUS_OPTIONS}
              value={status}
              onValueChange={(value) => updateParams({ status: value ?? "" })}
            >
              <SelectTrigger variant="outlined" className="w-36" aria-label="状态筛选">
                <SelectValue placeholder="全部状态" />
              </SelectTrigger>
              <SelectContent>
                {STATUS_OPTIONS.map((option) => (
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
        <Alert className="mb-4">{failureMessage(query.error, "审批单加载失败")}</Alert>
      ) : null}

      <Table
        columns={columns}
        dataSource={records}
        rowKey="id"
        size="medium"
        loading={query.isPending}
        emptyText="暂无审批单"
        pagination={false}
        scroll={{ x: 900 }}
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
