import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

import type { CompileVO, ExecuteVO, QueryDsl } from "./types";

export { buildQueryDsl } from "./dsl";

const QUERY_API = `${API_PREFIX}/query`;

export const compileQuery = (dsl: QueryDsl): Promise<CompileVO> =>
  HttpUtils.postData<CompileVO>(`${QUERY_API}/compile`, dsl);

export const executeQuery = (dsl: QueryDsl): Promise<ExecuteVO> =>
  HttpUtils.postData<ExecuteVO>(`${QUERY_API}/execute`, dsl);

export type {
  AnalysisMode,
  CompileVO,
  DslDraft,
  ExecuteVO,
  FilterOperator,
  QueryDsl,
} from "./types";
