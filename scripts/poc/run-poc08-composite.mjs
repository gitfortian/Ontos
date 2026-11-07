/**
 * PoC #8「复合意图拆分」最小探针（M0 / W3 / T3.3，判据级交付）。
 *
 * 判据（技术选型 v1.1 §7 #8）：≥30 道复合题；子查询独立编译 / 独立授权 / 结果隔离 100%
 * （合并 SQL 次数 0、共享中间结果集次数 0）；单子查询失败不阻断其他返回 100%；
 * parent_request_id / sub_intent_id 审计完整率 100%；depends_on 继承后重新校验授权 100%。
 *
 * M0 边界：编译器只支持单意图、sub_intents 未进入编译链路（见 PoC-04），
 * 因此本探针只交付「集可跑」的最小验证，完整验收待 M2 回填：
 *   1. 题集结构完整性（schema / seq 连续 / dependsOn 一致 / scope 对齐）；
 *   2. 题面卫生：题目与派生说明不得出现物理表名、物理列名或 SQL 关键字；
 *   3. 【可选 --api】对能映射到当前发布清单资产的子意图，逐个走 /api/v1/query/compile，
 *      验证「逐子意图独立编译」路径可行；其余子意图标注 unpublished_asset_m1。
 * 探针结果整体标注 pending_m2，不记为通过。
 *
 * 运行：node scripts/poc/run-poc08-composite.mjs [--api http://127.0.0.1:8080]
 * 产物：docs/06-verification/poc/PoC-08-probe-raw.json
 */
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(fileURLToPath(new URL("..", import.meta.url))); // scripts/
const repo = resolve(root, "..");
const setPath = resolve(repo, "docs/06-verification/evaluation/复合意图题集-v1.json");
const evidencePath = resolve(repo, "docs/06-verification/poc/PoC-08-probe-raw.json");

const API = (() => {
  const i = process.argv.indexOf("--api");
  return i > 0 ? process.argv[i + 1] : null;
})();

/** 物理名泄漏扫描：题面与派生说明只允许资产 ID 与中文，不得携带物理表/列/SQL。 */
const LEAK_PATTERNS = [
  /sales_order/i, /fact_order/i, /dim_customer/i, /amount/i, /order_date/i,
  /\bselect\b/i, /\bfrom\b/i, /\bjoin\b/i, /\bwhere\b/i, /\bgroup\s+by\b/i,
];

/** 当前 demo 清单已有的资产（其余标注 unpublished_asset_m1，待 M1 清单发布）。 */
const DEMO_ASSETS = new Set(["metric.gmv", "metric.order_count", "dim.region", "dim.channel", "dim.order_date"]);

const HEADERS = {
  "content-type": "application/json",
  "X-Ontos-Tenant": "demo",
  "X-Ontos-User": "demo",
  "X-Ontos-Session": "poc-08",
  "X-Ontos-Turn": "0",
};

function validateStructure(set) {
  const errors = [];
  const ids = new Set();
  const questions = new Set();
  const legalPlans = new Set(["none", "one", "all"]);
  const legalCategories = new Set(["result_dependent", "parallel_independent", "failure_single", "cross_scope", "composite_time"]);

  for (const c of set.cases) {
    if (ids.has(c.id)) errors.push(`${c.id}: id 重复`);
    if (questions.has(c.question)) errors.push(`${c.id}: 题目重复`);
    ids.add(c.id);
    questions.add(c.question);
    if (!legalCategories.has(c.category)) errors.push(`${c.id}: 非法 category ${c.category}`);
    if (!legalPlans.has(c.failurePlan)) errors.push(`${c.id}: 非法 failurePlan ${c.failurePlan}`);
    if (!Array.isArray(c.expectedSubIntents) || c.expectedSubIntents.length < 2) {
      errors.push(`${c.id}: 子意图少于 2 个`);
      continue;
    }
    if (c.scopePerSubIntent.length !== c.expectedSubIntents.length) {
      errors.push(`${c.id}: scopePerSubIntent 与子意图数量不一致`);
    }
    const seqs = c.expectedSubIntents.map((s) => s.seq);
    seqs.forEach((seq, i) => {
      if (seq !== i + 1) errors.push(`${c.id}: 子意图 seq 不连续（位置 ${i} 值 ${seq}）`);
    });
    for (const dep of c.dependsOn ?? []) {
      if (!seqs.includes(dep)) errors.push(`${c.id}: dependsOn 引用不存在的 seq ${dep}`);
    }
    for (const s of c.expectedSubIntents) {
      for (const dep of s.dependsOn ?? []) {
        if (!seqs.includes(dep)) errors.push(`${c.id}: 子意图 ${s.seq} 依赖不存在的 seq ${dep}`);
        if (dep >= s.seq) errors.push(`${c.id}: 子意图 ${s.seq} 依赖了非前序 seq ${dep}（依赖必须构成 DAG）`);
      }
      if ((s.dependsOn ?? []).length > 0 && !c.dependsOn?.includes(s.seq)) {
        errors.push(`${c.id}: 子意图 ${s.seq} 有依赖但未登记到顶层 dependsOn`);
      }
      if (c.failurePlan === "one" && !c.expectedSubIntents.some((x) => x.failureInjection != null)) {
        errors.push(`${c.id}: failurePlan=one 但未标注允许失败的子意图`);
      }
    }
    // 题面卫生：不得出现物理名
    const texts = [c.question, ...(c.expectedSubIntents.map((s) => s.derive ?? ""))];
    for (const text of texts) {
      for (const pattern of LEAK_PATTERNS) {
        if (pattern.test(text)) errors.push(`${c.id}: 题面疑似物理名/SQL 泄漏: ${pattern} in "${text.slice(0, 40)}"`);
      }
    }
  }
  return errors;
}

async function compileSubIntent(subIntent) {
  const dsl = {
    manifestId: "2026.11.06-demo",
    mode: "QUERY",
    metricId: subIntent.assetRef,
    dimensions: subIntent.dimensions ?? [],
    limit: 50,
  };
  const res = await fetch(`${API}/api/v1/query/compile`, {
    method: "POST",
    headers: HEADERS,
    body: JSON.stringify(dsl),
  });
  const body = await res.json().catch(() => ({}));
  return { httpStatus: res.status, code: body.code, sql: body.data?.sql ?? null };
}

const set = JSON.parse(readFileSync(setPath, "utf8"));
const structureErrors = validateStructure(set);

const live = { enabled: Boolean(API), cases: {} };
if (API) {
  for (const c of set.cases) {
    live.cases[c.id] = [];
    for (const sub of c.expectedSubIntents) {
      if (!DEMO_ASSETS.has(sub.assetRef)) {
        live.cases[c.id].push({ seq: sub.seq, assetRef: sub.assetRef, status: "unpublished_asset_m1" });
        continue;
      }
      if (sub.intent !== "QUERY") {
        live.cases[c.id].push({ seq: sub.seq, assetRef: sub.assetRef, status: "mode_pending_m2", intent: sub.intent });
        continue;
      }
      try {
        const r = await compileSubIntent(sub);
        live.cases[c.id].push({
          seq: sub.seq,
          assetRef: sub.assetRef,
          status: r.code === 0 ? "compiled_independent" : `rejected_code_${r.code}`,
          sqlPreview: r.sql ? r.sql.slice(0, 80) : null,
        });
      } catch (err) {
        live.cases[c.id].push({ seq: sub.seq, assetRef: sub.assetRef, status: "http_error", error: String(err) });
      }
    }
  }
}

const result = {
  probe: "PoC-08 最小探针（判据级交付）",
  criteriaRef: "技术选型 v1.1 §7 #8",
  setStatus: "pending_m2 —— 结构与卫生已验证；独立授权/结果隔离/失败注入/审计链路待 M2 完整验收",
  setVersion: set.version,
  caseCount: set.cases.length,
  criteriaMinCases: 30,
  caseCountMeetsCriteria: set.cases.length >= 30,
  structureErrors,
  live,
};
writeFileSync(evidencePath, JSON.stringify(result, null, 2));

console.log(`题集 ${set.cases.length} 题（判据下限 30）：${set.cases.length >= 30 ? "达标" : "不足"}`);
console.log(`结构错误：${structureErrors.length}`);
for (const e of structureErrors) console.log(`  - ${e}`);
if (API) {
  const statuses = Object.values(live.cases).flat().map((x) => x.status);
  const tally = {};
  for (const s of statuses) tally[s] = (tally[s] ?? 0) + 1;
  console.log(`在线子意图编译状态：${JSON.stringify(tally)}`);
} else {
  console.log("未提供 --api，跳过在线段（结构验证已完成）");
}
console.log(`产物：${evidencePath}`);
if (structureErrors.length > 0 || set.cases.length < 30) process.exit(1);
