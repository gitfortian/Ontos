import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

// 业务错误类型经域 service 转发：app 层只依赖 service/<域>，不直连 service/http（架构约束）。
export { BizError } from "@/service/http/request";

export type { AskCandidate, AskRunEventVO, AskRunRequest, AskTerminalVO } from "./types";

import type { AskRunEventVO, AskRunRequest, AskTerminalVO } from "./types";

const ASK_API = `${API_PREFIX}/ask`;

/** 发起一轮 ask 对话（run 创建 + 编排 + 终态；审批等待超 30s 返回 waiting 中间态）。 */
export const createAskRun = (request: AskRunRequest): Promise<AskTerminalVO> =>
  HttpUtils.postData<AskTerminalVO>(`${ASK_API}/runs`, request);

/** run 事件只读重放（afterSeq 断线重连；重复请求不触发任何执行）。 */
export const replayAskEvents = (runId: string, afterSeq = 0): Promise<AskRunEventVO[]> =>
  HttpUtils.getData<AskRunEventVO[]>(
    `${ASK_API}/run/${encodeURIComponent(runId)}/events?afterSeq=${afterSeq}`,
  );
