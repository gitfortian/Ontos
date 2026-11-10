import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

import type { AuditLogVO, FailureDashboardVO, PageResult } from "./types";

const AUDIT_API = `${API_PREFIX}/audit-logs`;
const FAILURE_API = `${API_PREFIX}/evaluation/failure-samples`;

export const listAuditLogs = (
  action: string,
  pageNo: number,
  signal?: AbortSignal,
): Promise<PageResult<AuditLogVO>> => {
  const query = new URLSearchParams({ pageNo: String(pageNo), pageSize: "20" });
  if (action) query.set("action", action);
  return HttpUtils.getData<PageResult<AuditLogVO>>(`${AUDIT_API}?${query}`, { signal });
};

/** 失败看板：五维下钻（G-5）+ 最近样本明细。 */
export const getFailureDashboard = (
  setId: string,
  recentLimit = 50,
  signal?: AbortSignal,
): Promise<FailureDashboardVO> => {
  const query = new URLSearchParams({ setId, recentLimit: String(recentLimit) });
  return HttpUtils.getData<FailureDashboardVO>(`${FAILURE_API}/dashboard?${query}`, { signal });
};

export type { AuditLogVO, FailureDashboardVO, PageResult } from "./types";
