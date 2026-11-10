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

export interface FailureSampleVO {
  id: number;
  setId: string;
  questionId: string;
  runId: string;
  scenarioEntryMode: string;
  queryShape: string;
  turnType: string;
  isComposite: boolean;
  riskType: string;
  expectedTerminal?: string;
  actualTerminal?: string;
  errorCode?: number;
  reason?: string;
  createdAt?: string;
}

export interface FailureDashboardVO {
  setId: string;
  total: number;
  dimensions: Record<string, Array<{ value: string; count: number }>>;
  recentSamples: FailureSampleVO[];
  recentLimit: number;
}
