import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

/** 侧栏会话（按最近活跃排序；标题取最近一条有话术的轮次）。 */
export interface AskSessionVO {
  sessionId: string;
  title: string;
  runCount: number;
  lastActiveAt: string;
}

export const listAskSessions = (limit = 20, signal?: AbortSignal): Promise<AskSessionVO[]> =>
  HttpUtils.getData<AskSessionVO[]>(`${API_PREFIX}/ask/sessions?limit=${limit}`, { signal });

/** 会话内轮次摘要（终态事件回放；只读，不触发任何执行）。 */
export interface AskSessionRunVO {
  runId: string;
  turnIndex: number;
  status: string;
  createdAt: string;
  utterance: string | null;
  /** 终态名（query/clarification/discovery/out_of_capability/composite/waiting）。 */
  terminal: string | null;
  /** 终态业务话术（copy.text，服务端模板生成）。 */
  summaryText: string | null;
  rowCount: number | null;
}

export const fetchSessionRuns = (
  sessionId: string,
  limit = 20,
  signal?: AbortSignal,
): Promise<AskSessionRunVO[]> =>
  HttpUtils.getData<AskSessionRunVO[]>(
    `${API_PREFIX}/ask/sessions/${encodeURIComponent(sessionId)}/runs?limit=${limit}`,
    { signal },
  );
