import type { DslDraft, QueryDsl } from "./types";

/**
 * Splits a comma-separated dimension input into asset IDs without empty entries.
 *
 * 时间窗只在「表达式 + 时间维度」都填了才带上：只有一半的输入在服务端会被拒
 * （缺 field 报 1002000008、绝对日期报 1002000009），前端不做猜测补齐。
 */
export const buildQueryDsl = (draft: DslDraft): QueryDsl => {
  const dimensions = draft.dimensions
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
  const timeExpression = draft.timeExpression.trim();
  const timeField = draft.timeField.trim();
  const timeWindow =
    timeExpression && timeField
      ? { timeExpressionRaw: timeExpression, timeRange: { field: timeField } }
      : {};

  return {
    manifestId: draft.manifestId,
    mode: "QUERY",
    metricId: draft.metricId,
    dimensions,
    limit: draft.limit,
    ...timeWindow,
  };
};
