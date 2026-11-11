import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

import type {
  AuditLogVO,
  FailureCauseCategoryVO,
  FailureDashboardVO,
  FailureRegressionTarget,
  FailureSampleDetailVO,
  FailureSampleStatus,
  FailureSampleStatusResultVO,
  PageResult,
} from "./types";

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

export interface FailureDashboardQuery {
  setId: string;
  recentLimit?: number;
  /** 单维联动：维度与取值必须同时给出（分布始终基于全集）。 */
  drillDimension?: string;
  drillValue?: string;
}

/** 失败看板：五维下钻（G-5）+ 单维联动过滤 + 最近样本明细。 */
export const getFailureDashboard = (
  options: FailureDashboardQuery,
  signal?: AbortSignal,
): Promise<FailureDashboardVO> => {
  const query = new URLSearchParams({
    setId: options.setId,
    recentLimit: String(options.recentLimit ?? 50),
  });
  if (options.drillDimension && options.drillValue) {
    query.set("drillDimension", options.drillDimension);
    query.set("drillValue", options.drillValue);
  }
  return HttpUtils.getData<FailureDashboardVO>(`${FAILURE_API}/dashboard?${query}`, { signal });
};

/** 样本详情：当前状态 + 根因分类 + 状态/分类事件历史。 */
export const getFailureSampleDetail = (
  id: number,
  signal?: AbortSignal,
): Promise<FailureSampleDetailVO> =>
  HttpUtils.getData<FailureSampleDetailVO>(`${FAILURE_API}/${id}`, { signal });

/** 状态流转（regressed 不在可选项内——必须走回流通道）。 */
export const transitionFailureSample = (
  id: number,
  toStatus: FailureSampleStatus,
  note?: string,
): Promise<FailureSampleStatusResultVO> =>
  HttpUtils.postData<FailureSampleStatusResultVO>(`${FAILURE_API}/${id}/status`, {
    toStatus,
    note,
  });

/** 根因分类（闭词表由后端判定，未知分类拒绝）。 */
export const classifyFailureSample = (
  id: number,
  category: string,
  note?: string,
): Promise<FailureSampleStatusResultVO> =>
  HttpUtils.postData<FailureSampleStatusResultVO>(`${FAILURE_API}/${id}/classify`, {
    category,
    note,
  });

/** 退回回归集（只允许 dev / validation；返回 regressionSampleId 供落题集）。 */
export const returnFailureSampleToRegression = (
  id: number,
  targetSet: FailureRegressionTarget,
  note?: string,
): Promise<FailureSampleStatusResultVO> =>
  HttpUtils.postData<FailureSampleStatusResultVO>(`${FAILURE_API}/${id}/return-to-regression`, {
    targetSet,
    note,
  });

/** 根因分类闭词表（分类下拉的唯一来源，前端不复制词表）。 */
export const listFailureCauseCategories = (
  signal?: AbortSignal,
): Promise<FailureCauseCategoryVO[]> =>
  HttpUtils.getData<FailureCauseCategoryVO[]>(`${FAILURE_API}/categories`, { signal });

export type {
  AuditLogVO,
  FailureCauseCategoryVO,
  FailureDashboardVO,
  FailureRegressionTarget,
  FailureSampleDetailVO,
  FailureSampleStatus,
  FailureSampleStatusResultVO,
  FailureSampleVO,
  FailureStatusEventVO,
  FailureClassificationVO,
  PageResult,
} from "./types";
