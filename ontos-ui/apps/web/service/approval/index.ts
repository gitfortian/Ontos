import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

import type { ApprovalDecision, ApprovalVO, PageResult } from "./types";

const APPROVAL_API = `${API_PREFIX}/approvals`;

export const listApprovals = (
  status: string,
  pageNo: number,
  signal?: AbortSignal,
): Promise<PageResult<ApprovalVO>> => {
  const query = new URLSearchParams({ pageNo: String(pageNo), pageSize: "20" });
  if (status) query.set("status", status);
  return HttpUtils.getData<PageResult<ApprovalVO>>(`${APPROVAL_API}?${query}`, { signal });
};

export const decideApproval = (
  id: string,
  decision: ApprovalDecision,
  reason?: string,
): Promise<boolean> =>
  HttpUtils.postData<boolean>(`${APPROVAL_API}/${encodeURIComponent(id)}/decide`, {
    decision,
    reason,
  });

export type { ApprovalDecision, ApprovalStatus, ApprovalVO, PageResult } from "./types";
