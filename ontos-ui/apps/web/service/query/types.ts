export type AnalysisMode =
  | "QUERY"
  | "LOOKUP"
  | "LOOKUP_BATCH"
  | "FUNNEL"
  | "YOY_MOM"
  | "ATTRIBUTION"
  | "RETENTION"
  | "DISCOVERY";

export type FilterOperator = "EQ" | "IN" | "NOT_IN" | "GTE" | "LTE" | "BETWEEN";

/** Type-safe DSL: asset IDs only, never physical table or column names. */
export interface QueryDsl {
  manifestId: string;
  scenarioId?: string;
  mode?: AnalysisMode;
  metricId: string;
  dimensions?: string[];
  filters?: Array<{
    fieldId: string;
    operator: FilterOperator;
    values: string[];
  }>;
  timeRange?: { field: string; from?: string; to?: string };
  /** 相对时间表达，由服务端解析器换算；客户端不得提交绝对日期（校验器 1002000009）。 */
  timeExpressionRaw?: string;
  limit?: number;
}

export interface DslDraft {
  manifestId: string;
  metricId: string;
  dimensions: string;
  /** 相对时间表达（如「近 30 天」）；留空表示不带时间窗。 */
  timeExpression: string;
  /** 时间维度资产 ID，相对时间解析与时间过滤都靠它；留空则不带时间窗。 */
  timeField: string;
  limit: number;
}

export interface CompileVO {
  manifestId: string;
  contentHash: string;
  metricId: string;
  dimensions: string[];
  dialect: string;
  sql: string;
  bindValues: unknown[];
  appliedLimit: number;
}

/** 服务端解析出的 UNIQUE Join 路径，仅供调试展示；物理列名来自清单，不参与前端拼接。 */
export interface ResolvedJoin {
  relationId: string;
  table: string;
  leftColumn: string;
  rightColumn: string;
  cardinality: string;
  direction: string;
}

/** dry-plan 响应：同一编译产物 + EXPLAIN 计划与成本，status 由服务端判定。 */
export interface PlanVO {
  manifestId: string;
  contentHash: string;
  mode: string;
  sql: string;
  bindValues: unknown[];
  appliedLimit: number;
  joins: ResolvedJoin[];
  planJson: string | null;
  estimatedCost: number;
  estimatedRows: number;
  /** OK / BLOCKED；null 表示只做过编译、没有 EXPLAIN。 */
  status: string | null;
  blockReason: string | null;
  verified: boolean;
  compiledAt?: string | null;
}

/** dry-run 响应：计划字段 + 受限执行结果（行数上限比正式执行更紧）。 */
export interface DryRunVO extends PlanVO {
  columns: string[];
  rows: Array<Record<string, unknown>>;
  rowCount: number;
  truncated: boolean;
  elapsedMs: number;
  /** true 表示结果来自 query_cache，未重新编译执行。 */
  cached?: boolean;
}

export interface ExecuteVO {
  manifestId: string;
  contentHash: string;
  columns: string[];
  rows: Array<Record<string, unknown>>;
  rowCount: number;
  truncated: boolean;
  elapsedMs: number;
  /** true 表示结果来自 query_cache，未重新编译执行。 */
  cached?: boolean;
}
