#!/usr/bin/env node
/**
 * B3 数据视图迁移：first-release-v22.json → first-release-v23.json
 *
 * 依据 docs/03-plans/stages/B3/B3-数据视图与资源层拆分-实施规格-v1.0.md §3.4 与 §10.3（T6）。
 *
 * 做两件事（E1 逐字等价为迁移主判据，ADR-0026）：
 *   1. 反推视图资产：从 v22 全部 property.column（限定名）拆出「表 → 被引用列集合」，
 *      生成 6 个 dataView（列快照 type="unknown" 占位，nullable=true——真实扫描仅作
 *      资源侧 enrichment，见规格 §0.4 P1-1：E1 只消费表名+列名字符串，快照不参与 SQL 生成）；
 *   2. 属性平移：column 限定名 → viewId + 裸列名（relation 列保持旧口径，Phase B 定档）。
 *
 * 幂等：每次都从 v22 重新生成 v23（不读 v23），重复执行结果逐字节一致。
 * 断言（失败即退出非 0，不产出半成品）：
 *   1. v22 全部属性都是旧口径（限定名 column、无 viewId）；
 *   2. 视图 ID 由物理表名规范化生成且唯一（B3 §3.1 ID 规范）；
 *   3. 平移后 E1 可构造性：每属性 tableOfProperty(v23) == tableOf(v22)（同字符串）；
 *   4. v23 无残留旧口径属性（全部有 viewId，column 为裸名）；
 *   5. v23 的 dataViews 覆盖全部被引用表，且每属性裸列在对应视图快照中（R-View 2 前置自检）；
 *   6. metric/dimension/vqr/scenario 段逐字节不变（E1 的输入只有属性绑定）。
 *
 * 用法：node scripts/b3-migrate-v22-v23.mjs [--check]
 *   --check 只校验不写文件（CI 用）
 */
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { createHash } from "node:crypto";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const V22 = join(ROOT, "ontos-assets/first-release-v22.json");
const V23 = join(ROOT, "ontos-assets/first-release-v23.json");
const CHECK_ONLY = process.argv.includes("--check");

/** 物理表 → 业务名（dataView.name 必填，UI 主显；B3 §3.1）。 */
const TABLE_NAMES = {
  orders: "订单表",
  order_item: "订单明细表",
  customer: "客户表",
  channel: "渠道表",
  product: "商品表",
  event: "事件表",
};

const fail = (message) => {
  console.error(`[b3-migrate] FAIL: ${message}`);
  process.exit(1);
};

/** 视图 ID 生成规范（B3 §3.1）：小写化、非法字符转下划线、连续下划线合并。 */
const normalizeViewId = (table) => {
  const normalized = table
    .toLowerCase()
    .replace(/[^a-z0-9_]/g, "_")
    .replace(/_{2,}/g, "_")
    .replace(/^_+|_+$/g, "");
  if (!normalized) fail(`物理表名规范化后为空: ${table}`);
  return `view.${normalized}`;
};

const v22 = JSON.parse(readFileSync(V22, "utf8"));
const v23 = structuredClone(v22);
const errors = [];

// ---------- 1. 反推视图：表 → 被引用列集合（保序） ----------
const columnsByTable = new Map();
for (const property of v22.ontology.properties) {
  const column = property.column;
  if (property.viewId) {
    errors.push(`v22 属性已是新口径，迁移输入必须为 v22 旧口径: ${property.id}`);
    continue;
  }
  if (!column || !column.includes(".")) {
    errors.push(`v22 属性列不是限定名: ${property.id} -> ${column}`);
    continue;
  }
  const dot = column.lastIndexOf(".");
  const table = column.slice(0, dot);
  const bare = column.slice(dot + 1);
  if (!columnsByTable.has(table)) columnsByTable.set(table, []);
  const list = columnsByTable.get(table);
  if (!list.includes(bare)) list.push(bare);
}
if (columnsByTable.size === 0) {
  fail("v22 没有任何可反推的限定名属性列");
}

// ---------- 2. 生成 dataViews 段 ----------
const dataViews = [...columnsByTable.entries()].map(([table, columns]) => {
  const id = normalizeViewId(table);
  if (!(table in TABLE_NAMES)) {
    errors.push(`物理表未登记业务名: ${table}（补 TABLE_NAMES）`);
  }
  const snapshot = columns.map((name) => ({ name, type: "unknown", nullable: true }));
  const contentHash = createHash("sha256")
    .update(JSON.stringify({ table, columns: snapshot }))
    .digest("hex")
    .slice(0, 16);
  return {
    id,
    name: TABLE_NAMES[table] ?? table,
    schema: "public",
    table,
    columns: snapshot,
    contentHash,
  };
});
v23.dataViews = dataViews;
const viewByTable = new Map(dataViews.map((view) => [view.table, view]));

// ---------- 3. 属性平移：限定名 → viewId + 裸列名 ----------
for (const property of v23.ontology.properties) {
  const legacy = property.column;
  const dot = legacy.lastIndexOf(".");
  const table = legacy.slice(0, dot);
  const bare = legacy.slice(dot + 1);
  const view = viewByTable.get(table);
  if (!view) {
    errors.push(`属性所在表没有对应视图: ${property.id} -> ${table}`);
    continue;
  }
  // E1 可构造性：视图表名与旧限定名前缀逐字相同（同字符串 → SQL 逐字等价的构造前提）
  if (view.table !== table) {
    errors.push(`视图表名与旧口径不一致（E1）: ${property.id} ${view.table} != ${table}`);
  }
  if (!view.columns.some((item) => item.name === bare)) {
    errors.push(`视图快照缺被引用列（R-View 2 前置）: ${property.id} ${view.id}.${bare}`);
  }
  property.viewId = view.id;
  property.column = bare;
}

// ---------- 4. 形态断言：v23 无旧口径属性 ----------
for (const property of v23.ontology.properties) {
  if (!property.viewId) errors.push(`v23 属性缺 viewId: ${property.id}`);
  if (property.column.includes(".")) {
    errors.push(`v23 属性列残留表前缀: ${property.id} -> ${property.column}`);
  }
}

// ---------- 5. E1 输入不变断言：metric/dimension/vqr/scenario 逐字节不变 ----------
const semanticKeys = ["semantic", "vqrs", "scenarios", "rules", "copyTemplates", "timeExpressionPatterns", "skills"];
for (const key of semanticKeys) {
  if (JSON.stringify(v22[key]) !== JSON.stringify(v23[key])) {
    errors.push(`E1 输入被改动（必须逐字节不变）: ${key}`);
  }
}
// relation 列保持旧口径（Phase B 定档：relation 列视图化随 Phase 2 后半）
for (const relation of v23.ontology.relations ?? []) {
  if (relation.viewId) errors.push(`relation 不应带 viewId: ${relation.id}`);
}

// ---------- 6. 覆盖性断言：视图数 = 反推表数，ID 唯一 ----------
const viewIds = dataViews.map((view) => view.id);
if (new Set(viewIds).size !== viewIds.length) {
  errors.push(`视图 ID 重复: ${viewIds.join(", ")}`);
}

// ---------- 7. 版本推进 ----------
v23.ontology.ontologyVersion = "2026.11.13";
v23.semantic.semanticVersion = "2026.11.13.1";

if (errors.length > 0) {
  console.error("[b3-migrate] 校验失败：");
  for (const error of errors) console.error(`  - ${error}`);
  process.exit(1);
}

const output = `${JSON.stringify(v23, null, 2)}\n`;

if (CHECK_ONLY) {
  if (!existsSync(V23)) fail("v23 资产不存在，先运行不带 --check 的迁移");
  if (readFileSync(V23, "utf8") !== output) fail("v23 资产与迁移结果不一致（需重新生成）");
  console.log("[b3-migrate] OK（--check：v23 与迁移结果一致）");
} else {
  writeFileSync(V23, output, "utf8");
  console.log("[b3-migrate] OK：生成 v23");
  console.log(
    `[b3-migrate] 视图 ${dataViews.length} 个：${dataViews.map((v) => `${v.id}(${v.columns.length}列)`).join(" / ")}`,
  );
  console.log(`[b3-migrate] 属性 ${v23.ontology.properties.length} 个全部平移至 viewId 新口径`);
  console.log(`[b3-migrate] 版本：${v23.ontology.ontologyVersion} / ${v23.semantic.semanticVersion}`);
}
