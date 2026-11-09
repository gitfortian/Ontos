export interface CaliberCandidate {
  variantId: string;
  alias: string;
}

/**
 * 从 CALIBER_AMBIGUOUS 的错误信息里解析候选变体（M3/W2/T5.2）。
 *
 * 服务端消息形态固定为「…（候选: <指标ID> 候选: id（别名） / id（别名））」，
 * 候选段内部含全角括号，因此按「最后一个『候选: 』取到串尾再去掉收尾括号」切分；
 * 解析失败返回空数组（调用方回退为直接展示原文）。
 */
export const parseCaliberCandidates = (message: string): CaliberCandidate[] => {
  const start = message.lastIndexOf("候选: ");
  if (start < 0) return [];
  const tail = message.slice(start + "候选: ".length).replace(/）$/, "");
  return tail
    .split(" / ")
    .map((item) => {
      const sep = item.lastIndexOf("（");
      if (sep < 0) return { variantId: item.trim(), alias: "" };
      return { variantId: item.slice(0, sep).trim(), alias: item.slice(sep + 1, -1) };
    })
    .filter((candidate) => candidate.variantId.length > 0);
};
