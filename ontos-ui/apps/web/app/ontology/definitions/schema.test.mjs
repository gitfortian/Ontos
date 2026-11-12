import assert from "node:assert/strict";
import { test } from "node:test";

import {
  dimensionFormSchema,
  metricFormSchema,
  metricFormValues,
  splitAliases,
  toMetricDefinition,
} from "./schema.ts";

const METRIC = {
  id: "metric.order_amount",
  name: "订单金额",
  summary: "订单成交金额合计",
  aliases: "订单额，成交额",
  objectId: "concept.order",
  propertyId: "property.order.amount",
  aggregation: "SUM",
  allowedModes: ["QUERY"],
  allowedDimensions: ["dim.region"],
  sensitive: false,
};

test("splits aliases by ascii and chinese commas and drops blanks", () => {
  assert.deepEqual(splitAliases(" 成交额, 销售额 ，, 流水 "), ["成交额", "销售额", "流水"]);
});

test("requires property for non-count aggregation", () => {
  const result = metricFormSchema.safeParse({ ...METRIC, propertyId: "" });
  assert.equal(result.success, false);
  assert.ok(result.error.issues.some((issue) => issue.path.join(".") === "propertyId"));
});

test("allows empty property for count aggregation", () => {
  const result = metricFormSchema.safeParse({
    ...METRIC,
    aggregation: "COUNT",
    propertyId: "",
  });
  assert.equal(result.success, true);
});

test("builds definition without empty propertyId and with alias array", () => {
  const definition = toMetricDefinition({
    ...METRIC,
    aggregation: "COUNT",
    propertyId: "",
  });
  assert.equal(definition.id, "metric.order_amount");
  assert.equal("propertyId" in definition, false);
  assert.deepEqual(definition.aliases, ["订单额", "成交额"]);
  assert.deepEqual(definition.allowedDimensions, ["dim.region"]);
});

test("round-trips a stored definition into form values", () => {
  const values = metricFormValues(toMetricDefinition(METRIC));
  assert.equal(values.aliases, "订单额, 成交额");
  assert.equal(values.aggregation, "SUM");
  assert.deepEqual(values.allowedModes, ["QUERY"]);
});

test("dimension requires property", () => {
  const result = dimensionFormSchema.safeParse({
    id: "dim.region",
    name: "区域",
    summary: "订单所属区域",
    aliases: "大区",
    objectId: "concept.order",
    propertyId: "",
    sensitive: false,
  });
  assert.equal(result.success, false);
});
