import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

import type { CompileVO, DryRunVO, ExecuteVO, PlanVO, QueryDsl } from "./types";

export { buildQueryDsl } from "./dsl";

const QUERY_API = `${API_PREFIX}/query`;

export const compileQuery = (dsl: QueryDsl): Promise<CompileVO> =>
  HttpUtils.postData<CompileVO>(`${QUERY_API}/compile`, dsl);

export const executeQuery = (dsl: QueryDsl): Promise<ExecuteVO> =>
  HttpUtils.postData<ExecuteVO>(`${QUERY_API}/execute`, dsl);

/** dry-plan：编译 + EXPLAIN 成本估算，不触数据表；成本超阈值时 status=BLOCKED。 */
export const dryPlanQuery = (dsl: QueryDsl): Promise<PlanVO> =>
  HttpUtils.postData<PlanVO>(`${QUERY_API}/dry-plan`, dsl);

/** dry-run：同一产物受限执行（更紧的超时/行数），成本超阈值直接拒绝。 */
export const dryRunQuery = (dsl: QueryDsl): Promise<DryRunVO> =>
  HttpUtils.postData<DryRunVO>(`${QUERY_API}/dry-run`, dsl);

export type {
  AnalysisMode,
  CompileVO,
  DryRunVO,
  DslDraft,
  ExecuteVO,
  FilterOperator,
  PlanVO,
  QueryDsl,
  ResolvedJoin,
} from "./types";
