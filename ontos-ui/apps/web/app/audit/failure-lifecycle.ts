import type {
  FailureClassificationVO,
  FailureSampleStatus,
  FailureStatusEventVO,
} from "../../service/audit/types";

/**
 * 失败样本处置的纯逻辑（M7/W4/T4.3）：状态机动作表与事件时间线装配。
 *
 * <p>动作表必须与后端 `FailureSampleStatus.canTransitionTo` 一致：
 * `regressed` 只能由「退回回归集」通道写入，故任何状态都不在此表提供该目标
 * （后端同样拒绝，前端表只是把非法操作挡在按钮层面）。
 */
export interface StatusAction {
  to: FailureSampleStatus;
  label: string;
}

export const STATUS_LABEL: Record<FailureSampleStatus, string> = {
  open: "待分诊",
  triaged: "已分诊",
  fixed: "已修复",
  regressed: "已回流",
  ignored: "已忽略",
};

const NEXT_ACTIONS: Record<FailureSampleStatus, StatusAction[]> = {
  open: [{ to: "triaged", label: "开始分诊" }],
  triaged: [
    { to: "fixed", label: "标记已修复" },
    { to: "ignored", label: "忽略" },
  ],
  fixed: [{ to: "triaged", label: "重新分诊" }],
  regressed: [{ to: "triaged", label: "重新分诊" }],
  ignored: [{ to: "triaged", label: "重新分诊" }],
};

/** 未知状态按 open 处理会放宽权限，故显式返回空表（fail-closed）。 */
export function nextStatusActions(status: string): StatusAction[] {
  return NEXT_ACTIONS[status as FailureSampleStatus] ?? [];
}

export interface TimelineEntry {
  key: string;
  at: string;
  kind: "状态" | "分类";
  text: string;
  actor: string;
  note?: string | null;
}

/** 状态事件与分类事件合并为单条时间线（时间倒序，同刻按类型稳定排序）。 */
export function buildFailureTimeline(
  statusEvents: FailureStatusEventVO[] | undefined,
  classifications: FailureClassificationVO[] | undefined,
  categoryLabel: (code: string) => string,
): TimelineEntry[] {
  const entries: TimelineEntry[] = [
    ...(statusEvents ?? []).map((event) => ({
      key: `status-${event.id}`,
      at: event.createdAt ?? "",
      kind: "状态" as const,
      text: `${event.fromStatus ?? "open"} → ${event.toStatus}`,
      actor: event.actor,
      note: event.note,
    })),
    ...(classifications ?? []).map((item) => ({
      key: `category-${item.id}`,
      at: item.createdAt ?? "",
      kind: "分类" as const,
      text: categoryLabel(item.category),
      actor: item.actor,
      note: item.note,
    })),
  ];
  return entries.sort((left, right) => (left.at < right.at ? 1 : left.at > right.at ? -1 : 0));
}
