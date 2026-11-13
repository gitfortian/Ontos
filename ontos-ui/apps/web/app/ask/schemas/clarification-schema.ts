import { z } from "zod";

/**
 * 澄清回传强类型校验（批复第 4 条）：回传参数必须能被本轮候选集约束——
 * candidateId 不在候选集内一律拒绝提交（服务端 R5 还有同型闸门，这里是前端第一道）。
 */
export const buildClarificationSchema = (candidateIds: ReadonlySet<string>) =>
  z.object({
    candidateId: z
      .string()
      .min(1, "请先选择一个候选")
      .refine((id) => candidateIds.has(id), "回传候选不属于本轮候选集"),
  });

export type ClarificationFormValues = {
  candidateId: string;
};
