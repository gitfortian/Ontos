export interface AuditLogVO {
  id: number;
  requestId: string;
  parentRequestId?: string;
  subIntentId?: string;
  tenantId: string;
  userId: string;
  sessionId?: string;
  turnIndex?: number;
  manifestId?: string;
  action: string;
  metricId?: string;
  dsl?: string;
  sqlText?: string;
  rowCount?: number;
  reasonCode?: string;
  traceId?: string;
  createdAt?: string;
}

export interface PageResult<T> {
  total: number;
  list: T[];
}
