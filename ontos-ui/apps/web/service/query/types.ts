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
  timeRange?: { field: string; from: string; to: string };
  limit?: number;
}

export interface DslDraft {
  manifestId: string;
  metricId: string;
  dimensions: string;
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

export interface ExecuteVO {
  manifestId: string;
  contentHash: string;
  columns: string[];
  rows: Array<Record<string, unknown>>;
  rowCount: number;
  truncated: boolean;
  elapsedMs: number;
}
