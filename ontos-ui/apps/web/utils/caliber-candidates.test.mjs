import assert from "node:assert/strict";
import { test } from "node:test";
import { parseCaliberCandidates } from "./caliber-candidates.ts";

const SERVER_MESSAGE =
  "该指标存在多个口径，请指定 caliberVariantId（候选: metric.paid_gmv 候选: " +
  "metric.paid_gmv.online（线上支付口径） / metric.paid_gmv.offline（线下支付口径））";

test("parses server CALIBER_AMBIGUOUS candidates with aliases", () => {
  assert.deepEqual(parseCaliberCandidates(SERVER_MESSAGE), [
    { variantId: "metric.paid_gmv.online", alias: "线上支付口径" },
    { variantId: "metric.paid_gmv.offline", alias: "线下支付口径" },
  ]);
});

test("falls back to the variant id when no alias is present", () => {
  const message = "该指标存在多个口径，请指定 caliberVariantId（候选: m 候选: a / b）";
  assert.deepEqual(parseCaliberCandidates(message), [
    { variantId: "a", alias: "" },
    { variantId: "b", alias: "" },
  ]);
});

test("returns empty for unrelated messages", () => {
  assert.deepEqual(parseCaliberCandidates("维度不在指标允许范围: dim.region"), []);
  assert.deepEqual(parseCaliberCandidates(""), []);
});
