import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

// 业务错误类型经域 service 转发：app 层只依赖 service/<域>，不直连 service/http（架构约束）。
export { BizError } from "@/service/http/request";

import type {
  CompileVO,
  CopyTemplateVO,
  DryRunVO,
  ExecuteVO,
  PlanVO,
  QueryDsl,
  SkillDescriptor,
} from "./types";

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

/** 执行并取回请求 ID：服务端按 X-Request-Id 响应头返回，回放 /plan/{requestId} 依赖它。 */
export const executeQueryWithMeta = (
  dsl: QueryDsl,
): Promise<{ data: ExecuteVO; requestId: string | null }> =>
  HttpUtils.postWithMeta<ExecuteVO>(`${QUERY_API}/execute`, dsl);

/** 按 request_id 回放最近一次编译产物（SQL / 绑定值 / Join 路径 / 计划判定）。 */
export const replayPlan = (requestId: string): Promise<PlanVO> =>
  HttpUtils.getData<PlanVO>(`${QUERY_API}/plan/${encodeURIComponent(requestId)}`);

/** 已注册能力与槽位契约（M3：entity_lookup）。 */
export const listSkills = (signal?: AbortSignal): Promise<SkillDescriptor[]> =>
  HttpUtils.getData<SkillDescriptor[]>(`${QUERY_API}/skills`, { signal });

/** 能力调用：槽位 → DSL → 正式执行通道（校验/授权/审计/幂等与手工 DSL 一致）。 */
export const executeSkill = (skillId: string, slots: Record<string, unknown>): Promise<ExecuteVO> =>
  HttpUtils.postData<ExecuteVO>(`${QUERY_API}/skills/${encodeURIComponent(skillId)}`, { slots });

/**
 * 原因码 → 渲染文案（M3/W4）。占位符走 query 参数；未知原因码 / 占位符缺值时
 * 服务端回退 GENERIC 并置 fallback=true。
 */
export const getCopyTemplate = (
  reasonCode: string,
  params: Record<string, string> = {},
): Promise<CopyTemplateVO> => {
  const query = new URLSearchParams({ locale: "zh-CN", ...params });
  return HttpUtils.getData<CopyTemplateVO>(
    `${API_PREFIX}/copy-templates/${encodeURIComponent(reasonCode)}?${query.toString()}`,
  );
};

export type {
  AnalysisMode,
  CompileVO,
  CopyTemplateVO,
  DryRunVO,
  DslDraft,
  ExecuteVO,
  FilterOperator,
  PlanVO,
  QueryDsl,
  ResolvedJoin,
  SkillDescriptor,
  SkillSlot,
} from "./types";
