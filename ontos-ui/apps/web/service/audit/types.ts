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
  /** 当前生效的单维联动（为空 = 未过滤；分布始终基于全集）。 */
  drillDimension?: string | null;
  drillValue?: string | null;
  /** 联动命中条数（未过滤时等于 total）。 */
  filteredTotal: number;
}

/** 失败样本状态机（与后端 FailureSampleStatus 一致）。 */
export type FailureSampleStatus = "open" | "triaged" | "fixed" | "regressed" | "ignored";

export interface FailureStatusEventVO {
  id: number;
  fromStatus?: string | null;
  toStatus: string;
  actor: string;
  note?: string | null;
  createdAt?: string;
}

export interface FailureClassificationVO {
  id: number;
  category: string;
  actor: string;
  note?: string | null;
  createdAt?: string;
}

export interface FailureSampleDetailVO extends FailureSampleVO {
  status: FailureSampleStatus;
  category?: string | null;
  statusEvents: FailureStatusEventVO[];
  classifications: FailureClassificationVO[];
}

/** 状态流转 / 分类 / 回流 的统一返回视图。 */
export interface FailureSampleStatusResultVO {
  sampleId: number;
  status: FailureSampleStatus;
  category?: string | null;
  /** 仅回流通道返回。 */
  regressionSampleId?: string;
  targetSet?: string;
}

/** 根因分类闭词表项（后端 FailureCauseCategory 唯一来源）。 */
export interface FailureCauseCategoryVO {
  code: string;
  label: string;
}

/** 回流目标：test 集冻结只能升版，禁止直接写入。 */
export type FailureRegressionTarget = "dev" | "validation";
