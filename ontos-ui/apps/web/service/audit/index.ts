import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

import type { AuditLogVO, PageResult } from "./types";

const AUDIT_API = `${API_PREFIX}/audit-logs`;

export const listAuditLogs = (
  action: string,
  pageNo: number,
  signal?: AbortSignal,
): Promise<PageResult<AuditLogVO>> => {
  const query = new URLSearchParams({ pageNo: String(pageNo), pageSize: "20" });
  if (action) query.set("action", action);
  return HttpUtils.getData<PageResult<AuditLogVO>>(`${AUDIT_API}?${query}`, { signal });
};

export type { AuditLogVO, PageResult } from "./types";
