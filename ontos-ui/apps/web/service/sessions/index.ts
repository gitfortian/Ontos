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
