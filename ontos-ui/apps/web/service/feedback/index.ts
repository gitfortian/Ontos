import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

/** 结果反馈（W3 看板 👍👎）：DOWN 细分原因码与后端 AskFeedbackService 冻结一致。 */
export type AskFeedbackRating = "UP" | "DOWN";

export type AskFeedbackReasonCode = "WRONG_DATA" | "WRONG_CALIBER" | "NOT_ANSWERED" | "OTHER";

export interface AskFeedbackRequest {
  runId?: string;
  requestId?: string;
  rating: AskFeedbackRating;
  reasonCode?: AskFeedbackReasonCode;
  comment?: string;
}

/** 提交反馈；归属（租户/用户/会话）由服务端从请求上下文取，前端不传。 */
export const submitAskFeedback = (request: AskFeedbackRequest): Promise<number> =>
  HttpUtils.postData<number>(`${API_PREFIX}/ask/feedback`, request);

/** DOWN 细分原因展示文案（顺序即选项顺序）。 */
export const FEEDBACK_REASON_OPTIONS: Array<{
  code: AskFeedbackReasonCode;
  label: string;
}> = [
  { code: "WRONG_DATA", label: "数据不对" },
  { code: "WRONG_CALIBER", label: "口径不符合预期" },
  { code: "NOT_ANSWERED", label: "没有回答我的问题" },
  { code: "OTHER", label: "其它" },
];
