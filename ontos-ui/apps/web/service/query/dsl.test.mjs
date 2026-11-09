import assert from "node:assert/strict";
import { test } from "node:test";
import { buildQueryDsl } from "./dsl.ts";

const draft = {
  manifestId: "2026.11.06-demo",
  metricId: "metric.gmv",
  dimensions: "dim.region, dim.channel",
  timeExpression: "",
  timeField: "",
  caliberVariantId: "",
  limit: 50,
};

test("keeps the manifest anchor and the closed analysis mode", () => {
  const dsl = buildQueryDsl(draft);
  assert.equal(dsl.manifestId, "2026.11.06-demo");
  assert.equal(dsl.mode, "QUERY");
  assert.equal(dsl.metricId, "metric.gmv");
  assert.equal(dsl.limit, 50);
});

test("parses comma-separated asset IDs without empty entries", () => {
  assert.deepEqual(buildQueryDsl(draft).dimensions, ["dim.region", "dim.channel"]);
  assert.deepEqual(buildQueryDsl({ ...draft, dimensions: " , , " }).dimensions, []);
});

test("adds the relative time window only when both halves are present", () => {
  const withTime = buildQueryDsl({
    ...draft,
    timeExpression: "近 30 天",
    timeField: "dim.order_date",
  });
  assert.equal(withTime.timeExpressionRaw, "近 30 天");
  assert.deepEqual(withTime.timeRange, { field: "dim.order_date" });

  // 只填一半：不在前端补默认值，交给服务端 fail-closed 报错
  assert.equal(
    "timeExpressionRaw" in buildQueryDsl({ ...draft, timeExpression: "近 30 天" }),
    false,
  );
  assert.equal("timeRange" in buildQueryDsl({ ...draft, timeField: "dim.order_date" }), false);
});

test("does not add physical or transport-only fields", () => {
  const dsl = buildQueryDsl(draft);
  for (const key of ["table", "column", "sql", "join"]) {
    assert.equal(key in dsl, false, key);
  }
});

test("passes caliberVariantId through only when set (M3/W2/T5.2)", () => {
  // 澄清卡片回填后带上变体；空值不带字段，避免「无变体指标带空变体」在服务端被拒。
  assert.equal("caliberVariantId" in buildQueryDsl({ ...draft, caliberVariantId: "  " }), false);
  const withVariant = buildQueryDsl({
    ...draft,
    caliberVariantId: "metric.paid_gmv.online",
  });
  assert.equal(withVariant.caliberVariantId, "metric.paid_gmv.online");
});
