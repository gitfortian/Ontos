import type { DslDraft, QueryDsl } from "./types";

/** Splits a comma-separated dimension input into asset IDs without empty entries. */
export const buildQueryDsl = (draft: DslDraft): QueryDsl => ({
  manifestId: draft.manifestId,
  mode: "QUERY",
  metricId: draft.metricId,
  dimensions: draft.dimensions
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean),
  limit: draft.limit,
});
