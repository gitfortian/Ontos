#!/usr/bin/env node
/**
 * B0 资产迁移：first-release-v19.json → first-release-v20.json
 *
 * 依据 docs/03-plans/stages/B0/B0-语义模型三层化-实施规格-v1.1.md §3.3.2。
 *
 * 幂等：每次都从 v19 重新生成 v20（不读 v20），重复执行结果逐字节一致。
 * 断言（失败即退出非 0，不产出半成品）：
 *   1. 映射表覆盖 v19 全部常规指标与维度（ruleId 型除外）；
 *   2. v19 的 table.column 与目标 property.column **逐条相同**（E2 物理列不变）；
 *   3. 4 个新属性的 column 与 v19 缺失列逐条相同；
 *   4. 生成的 v20 中每个 metric/dimension 都有 objectId，且（除对象级计数外）有 propertyId；
 *   5. v20 中 metric/dimension 不再出现 table/column 字段。
 *
 * 用法：node scripts/b0-migrate-v19-v20.mjs [--check]
 *   --check 只校验不写文件（CI 用）
 */
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const V19 = join(ROOT, "ontos-assets/first-release-v19.json");
const V20 = join(ROOT, "ontos-assets/first-release-v20.json");
const CHECK_ONLY = process.argv.includes("--check");

/** 常规指标 → { objectId, propertyId }（ruleId 型不在表内） */
const METRIC_BINDING = {
  "metric.gmv": ["concept.order", "property.order.amount"],
  "metric.paid_gmv": ["concept.order", "property.order.amount"],
  "metric.gmv_created": ["concept.order", "property.order.amount"],
  "metric.order_count": ["concept.order", "property.order.order_no"],
  "metric.distinct_customers": ["concept.order", "property.order.customer_id"],
  "metric.avg_order_value": ["concept.order", "property.order.amount"],
  "metric.refund_amount": ["concept.order", "property.order.refund_amount"],
  "metric.order_item_quantity": ["concept.order_item", "property.order_item.quantity"],
};

/** 维度 → { objectId, propertyId } */
const DIMENSION_BINDING = {
  "dim.region": ["concept.order", "property.order.region"],
  "dim.channel": ["concept.order", "property.order.channel"],
  "dim.order_date": ["concept.order", "property.order.order_date"],
  "dim.customer_tier": ["concept.customer", "property.customer.tier"],
  "dim.product_category": ["concept.product", "property.product.category"],
};

/**
 * 新建属性（先于迁移落地）。name/aliases 按规格 §3.3.2 的命名策略：
 * 同列可复用维度词；跨列同名（customer_id / refund_amount）必须加限定。
 */
const NEW_PROPERTIES = [
  {
    id: "property.order.customer_id",
    name: "下单客户编号",
    column: "orders.customer_id",
    aliases: ["订单客户ID"],
    summary: "订单上的下单客户编号，指向客户主键",
    conceptId: "concept.order",
    valueType: "string",
    filterable: true,
    sensitivity: "INTERNAL",
    displayHints: [],
    lookupProjectable: false,
    existenceSensitive: false,
  },
  {
    id: "property.order.refund_amount",
    name: "订单退款金额",
    column: "orders.refund_amount",
    aliases: ["单笔退款金额"],
    summary: "单笔订单的退款金额原始值",
    conceptId: "concept.order",
    valueType: "decimal",
    filterable: true,
    sensitivity: "INTERNAL",
    displayHints: [],
    lookupProjectable: false,
    existenceSensitive: false,
  },
  {
    id: "property.order.channel",
    name: "渠道",
    column: "orders.channel",
    aliases: ["通路", "订单渠道"],
    summary: "订单所属的销售渠道（订单表冗余字段）",
    conceptId: "concept.order",
    valueType: "string",
    filterable: true,
    sensitivity: "INTERNAL",
    displayHints: [],
    lookupProjectable: true,
    existenceSensitive: false,
  },
  {
    id: "property.order.region",
    name: "区域",
    column: "orders.region",
    aliases: ["大区", "地区", "订单区域"],
    summary: "订单发货所属的大区",
    conceptId: "concept.order",
    valueType: "string",
    filterable: true,
    sensitivity: "INTERNAL",
    displayHints: [],
    lookupProjectable: true,
    existenceSensitive: false,
  },
];

const fail = (message) => {
  console.error(`[b0-migrate] FAIL: ${message}`);
  process.exit(1);
};

const v19 = JSON.parse(readFileSync(V19, "utf8"));
const v20 = structuredClone(v19);
const errors = [];

// ---------- 1. 加属性 ----------
const existingPropertyIds = new Set(v20.ontology.properties.map((p) => p.id));
for (const property of NEW_PROPERTIES) {
  if (existingPropertyIds.has(property.id)) {
    fail(`新属性已存在，迁移表与资产不一致: ${property.id}`);
  }
  v20.ontology.properties.push(property);
}
const propertyById = new Map(v20.ontology.properties.map((p) => [p.id, p]));

// ---------- 2. 改指标 ----------
for (const metric of v20.semantic.metrics) {
  if (metric.ruleId) {
    if (metric.table || metric.column) {
      errors.push(`ruleId 型指标不应带物理列: ${metric.id}`);
    }
    continue;
  }
  const binding = METRIC_BINDING[metric.id];
  if (!binding) {
    errors.push(`指标未在迁移表登记: ${metric.id}`);
    continue;
  }
  const [objectId, propertyId] = binding;
  const property = propertyById.get(propertyId);
  if (!property) {
    errors.push(`指标引用的属性不存在: ${metric.id} -> ${propertyId}`);
    continue;
  }
  // E2：v19 物理列必须与目标属性列逐条相同
  const legacy = `${metric.table}.${metric.column}`;
  if (legacy !== property.column) {
    errors.push(`物理列不一致（E2）: ${metric.id} ${legacy} != ${property.column}`);
  }
  delete metric.table;
  delete metric.column;
  metric.objectId = objectId;
  metric.propertyId = propertyId;
}

// ---------- 3. 改维度 ----------
for (const dimension of v20.semantic.dimensions) {
  const binding = DIMENSION_BINDING[dimension.id];
  if (!binding) {
    errors.push(`维度未在迁移表登记: ${dimension.id}`);
    continue;
  }
  const [objectId, propertyId] = binding;
  const property = propertyById.get(propertyId);
  if (!property) {
    errors.push(`维度引用的属性不存在: ${dimension.id} -> ${propertyId}`);
    continue;
  }
  const legacy = `${dimension.table}.${dimension.column}`;
  if (legacy !== property.column) {
    errors.push(`物理列不一致（E2）: ${dimension.id} ${legacy} != ${property.column}`);
  }
  delete dimension.table;
  delete dimension.column;
  dimension.objectId = objectId;
  dimension.propertyId = propertyId;
}

// ---------- 4. 覆盖性断言 ----------
const normalMetrics = v19.semantic.metrics.filter((m) => !m.ruleId);
if (Object.keys(METRIC_BINDING).length !== normalMetrics.length) {
  errors.push(
    `迁移表覆盖不全: 表 ${Object.keys(METRIC_BINDING).length} 条，v19 常规指标 ${normalMetrics.length} 条`,
  );
}
if (Object.keys(DIMENSION_BINDING).length !== v19.semantic.dimensions.length) {
  errors.push(
    `迁移表覆盖不全: 表 ${Object.keys(DIMENSION_BINDING).length} 条，v19 维度 ${v19.semantic.dimensions.length} 条`,
  );
}

// ---------- 5. 形态断言 ----------
for (const metric of v20.semantic.metrics) {
  if (metric.ruleId) continue;
  if (!metric.objectId) errors.push(`v20 指标缺 objectId: ${metric.id}`);
  if (!metric.propertyId) errors.push(`v20 指标缺 propertyId: ${metric.id}`);
  if ("table" in metric || "column" in metric) errors.push(`v20 指标残留物理名: ${metric.id}`);
}
for (const dimension of v20.semantic.dimensions) {
  if (!dimension.objectId) errors.push(`v20 维度缺 objectId: ${dimension.id}`);
  if (!dimension.propertyId) errors.push(`v20 维度缺 propertyId: ${dimension.id}`);
  if ("table" in dimension || "column" in dimension) errors.push(`v20 维度残留物理名: ${dimension.id}`);
}

// ---------- 6. 版本 ----------
v20.ontology.ontologyVersion = "2026.11.12";
v20.semantic.semanticVersion = "2026.11.12.1";

if (errors.length > 0) {
  console.error("[b0-migrate] 校验失败：");
  for (const error of errors) console.error(`  - ${error}`);
  process.exit(1);
}

const output = `${JSON.stringify(v20, null, 2)}\n`;

if (CHECK_ONLY) {
  if (!existsSync(V20)) fail("v20 资产不存在，先运行不带 --check 的迁移");
  const actual = readFileSync(V20, "utf8");
  if (actual !== output) fail("v20 资产与迁移结果不一致（需重新生成）");
  console.log("[b0-migrate] OK（--check：v20 与迁移结果一致）");
} else {
  writeFileSync(V20, output, "utf8");
  console.log(`[b0-migrate] OK：生成 ${V20}`);
  console.log(
    `[b0-migrate] 指标 ${v20.semantic.metrics.length} 条 / 维度 ${v20.semantic.dimensions.length} 条 / 属性 ${v20.ontology.properties.length} 条`,
  );
}
