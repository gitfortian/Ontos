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
const V21 = join(ROOT, "ontos-assets/first-release-v21.json");
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

// ---------- 7. v21：渠道维度语义升级 ----------
/**
 * 新增渠道对象维度。v19/v20 的 dim.channel 是订单表冗余列（orders.channel），
 * 只碰订单一个对象；本维度经 ontology.Order.attributedToChannel
 * （orders.channel_id -> channel.id）JOIN 到渠道对象，取 channel.name。
 *
 * 只加给订单级指标——订单项指标（metric.order_item_quantity）走 order_item -> order
 * -> channel 两跳，粒度边界需单独评审（ADR-0016/0018），本批不动。
 */
const CHANNEL_OBJECT_DIMENSION = {
  id: "dim.channel_name",
  name: "渠道名称",
  aliases: ["渠道明细", "渠道维表"],
  summary: "订单所属渠道的名称，关联渠道业务对象后跨表取值",
  sensitive: false,
  objectId: "concept.channel",
  propertyId: "property.channel.name",
};

const v21 = structuredClone(v20);
v21.semantic.dimensions.push(CHANNEL_OBJECT_DIMENSION);

for (const metric of v21.semantic.metrics) {
  const dimensions = metric.allowedDimensions;
  if (!dimensions || !dimensions.includes("dim.channel")) {
    continue;
  }
  const index = dimensions.indexOf("dim.channel");
  dimensions.splice(index + 1, 0, "dim.channel_name");
}

// VQR 改引用渠道对象维度，使模板真正跨「订单 × 渠道」两个对象
for (const vqr of v21.vqrs ?? []) {
  if (Array.isArray(vqr.dslTemplate?.dimensions)) {
    vqr.dslTemplate.dimensions = vqr.dslTemplate.dimensions.map((id) =>
      id === "dim.channel" ? "dim.channel_name" : id);
  }
  for (const condition of vqr.invalidationConditions ?? []) {
    if (condition.assetId === "dim.channel") {
      condition.assetId = "dim.channel_name";
    }
  }
}

// 两个渠道维度并存，描述必须能区分：dim.channel 是快照列，dim.channel_name 走对象
const legacyChannel = v21.semantic.dimensions.find((item) => item.id === "dim.channel");
if (legacyChannel) {
  legacyChannel.summary = "订单表记录的渠道名称快照，单表取值，不关联渠道业务对象";
}

v21.semantic.semanticVersion = "2026.11.12.2";

// v21 断言
const v21Errors = [];
const allowedByMetric = new Map(
  v21.semantic.metrics.map((metric) => [metric.id, metric.allowedDimensions ?? []]),
);
for (const metric of v21.semantic.metrics) {
  if (metric.allowedDimensions?.includes("dim.channel")
      && !metric.allowedDimensions.includes("dim.channel_name")) {
    v21Errors.push(`指标有 dim.channel 但未授权 dim.channel_name: ${metric.id}`);
  }
}
for (const vqr of v21.vqrs ?? []) {
  for (const dimensionId of vqr.dslTemplate?.dimensions ?? []) {
    const metricId = vqr.dslTemplate?.metric_id;
    const allowed = allowedByMetric.get(metricId);
    if (allowed && !allowed.includes(dimensionId)) {
      v21Errors.push(`VQR ${vqr.vqrId} 引用了未被 ${metricId} 授权的维度: ${dimensionId}`);
    }
  }
}
if (v21Errors.length > 0) {
  console.error("[b0-migrate] v21 校验失败：");
  for (const error of v21Errors) console.error(`  - ${error}`);
  process.exit(1);
}

const outputV21 = `${JSON.stringify(v21, null, 2)}\n`;

const output = `${JSON.stringify(v20, null, 2)}\n`;

if (CHECK_ONLY) {
  if (!existsSync(V20) || !existsSync(V21)) fail("v20/v21 资产不存在，先运行不带 --check 的迁移");
  if (readFileSync(V20, "utf8") !== output) fail("v20 资产与迁移结果不一致（需重新生成）");
  if (readFileSync(V21, "utf8") !== outputV21) fail("v21 资产与迁移结果不一致（需重新生成）");
  console.log("[b0-migrate] OK（--check：v20/v21 均与迁移结果一致）");
} else {
  writeFileSync(V20, output, "utf8");
  writeFileSync(V21, outputV21, "utf8");
  console.log(`[b0-migrate] OK：生成 ${V20} 与 ${V21}`);
  console.log(
    `[b0-migrate] v20：指标 ${v20.semantic.metrics.length} / 维度 ${v20.semantic.dimensions.length} / 属性 ${v20.ontology.properties.length}`,
  );
  console.log(
    `[b0-migrate] v21：维度 ${v21.semantic.dimensions.length}（新增 dim.channel_name）/ VQR ${(v21.vqrs ?? []).length}`,
  );
}
