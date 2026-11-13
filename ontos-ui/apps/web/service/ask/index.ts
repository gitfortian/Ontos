import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";
import { stream } from "@/service/http/request";

import { parseRunEvent } from "./events";

// 业务错误类型经域 service 转发：app 层只依赖 service/<域>，不直连 service/http（架构约束）。
export { BizError } from "@/service/http/request";

export type {
  AskCandidate,
  AskColumnMeta,
  AskRunEventVO,
  AskRunRequest,
  AskRunStreamEvent,
  AskStepEntry,
  AskStepPayload,
  AskStepStatus,
  AskTerminal,
  AskTerminalVO,
  AskToolPayload,
} from "./types";

export {
  customEventName,
  fromAguiEvent,
  parsePayload,
  reduceStep,
  STEP_EVENT_NAME,
  stepOf,
  TERMINAL_EVENT_NAME,
  terminalOf,
  TOOL_STEP_PREFIX,
  toolOf,
  toolStep,
} from "./events";

import type { AskRunEventVO, AskRunRequest, AskRunStreamEvent, AskTerminalVO } from "./types";

const ASK_API = `${API_PREFIX}/ask`;

/** 发起一轮 ask 对话（run 创建 + 编排 + 终态；审批等待超 30s 返回 waiting 中间态）。 */
export const createAskRun = (request: AskRunRequest): Promise<AskTerminalVO> =>
  HttpUtils.postData<AskTerminalVO>(`${ASK_API}/runs`, request);

/**
 * 流式一轮 ask（SSE）：与 createAskRun 走**同一条编排链路**，差别只是事件实时推送。
 *
 * <p>事件形状与 afterSeq 重放一致（`{seq, type, payload}`，payload 为 JSON 文本），
 * 断线后可用 replayAskEvents 从最后一条 seq 补齐，不会重复触发执行。
 *
 * <p>中止（signal）只停止本次展示：服务端编排会执行到终态并落审计。
 */
export const streamAskRun = async (
  request: AskRunRequest,
  onEvent: (event: AskRunStreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> => {
  await stream(`${ASK_API}/runs/stream`, {
    data: request,
    signal,
    onEvent: (raw) => {
      const parsed = parseRunEvent(raw);
      if (parsed) onEvent(parsed);
    },
  });
};

/** run 事件只读重放（afterSeq 断线重连；重复请求不触发任何执行）。 */
export const replayAskEvents = (runId: string, afterSeq = 0): Promise<AskRunEventVO[]> =>
  HttpUtils.getData<AskRunEventVO[]>(
    `${ASK_API}/run/${encodeURIComponent(runId)}/events?afterSeq=${afterSeq}`,
  );

/** 已发布且当前用户已授权的场景目录项（侧栏参考目录；进入仍由路由确认通道）。 */
export interface AskScenarioVO {
  scenarioId: string;
  name: string;
  summary: string | null;
  /** manual_only | recommend_or_confirm | auto_if_confident */
  entryMode: string | null;
}

/** 场景目录（只读事实呈现；不提供钉住/旁路语义——那是 M6 路由扩展，单独设计）。 */
export const listAskScenarios = (signal?: AbortSignal): Promise<AskScenarioVO[]> =>
  HttpUtils.getData<AskScenarioVO[]>(`${ASK_API}/scenarios`, { signal });
