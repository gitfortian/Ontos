#!/usr/bin/env node
/**
 * M6 全量深度审核 D-1 回归探针：直传场景裁决「失败即拒、目标状态不变」。
 *
 * 修复前：ScenarioRouteGuardImpl.adjudicate 在模式/scope/口径校验之前就 apply 写
 * in_scenario；且 prepare 后续 DSL/策略失败也会留状态，导致后续自由问数被错误裁剪。
 * 修复后：adjudicate 纯校验；commitExplicitRoute 仅在 prepare 全链成功后提交。
 *
 * 验证（同一会话，逐轮递增 turn）：
 *   T1 初始 scenario_state = free / 无行
 *   T2 scope 外资产直传 → 1004000007，状态仍 free
 *   T3 模式越界直传（RETENTION）→ 1004000005，状态仍 free
 *   T4 DSL 校验失败（场景内非法维度）→ 业务拒绝，状态仍 free
 *   T5 合法场景直传 → code 0，状态 = in_scenario（正向提交生效）
 *
 * 用法：node scripts/verify/scenario-state-failclosed.mjs --base-url http://127.0.0.1:8080
 */
import { execFileSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const OUT = resolve(repo, "docs/06-verification/evaluation/场景状态fail-closed证据-v1.json");
const BASE = process.argv.includes("--base-url")
  ? process.argv[process.argv.indexOf("--base-url") + 1]
  : "http://127.0.0.1:8080";
const PSQL = "D:/pg/bin/psql.exe";
const sql = (query) => execFileSync(PSQL,
  ["-U", "postgres", "-h", "localhost", "-d", "ontos", "-t", "-A", "-c", query],
  { env: { ...process.env, PGPASSWORD: "123456" } }).toString().trim();

const runId = `sc${Date.now()}`;
const user = `audit-${runId}`;
const session = `sess-${runId}`;
const HEADERS = (turn) => ({
  "Content-Type": "application/json", "X-Ontos-Tenant": "demo",
  "X-Ontos-User": user, "X-Ontos-Session": session,
  "X-Ontos-Turn": String(turn), "X-Request-Id": `sc-${runId}-${turn}`,
});
const stateOf = () => {
  const raw = sql(`SELECT scenario_state::text FROM session_state WHERE tenant_id='demo'`
    + ` AND user_id='${user}' AND session_id='${session}'`);
  if (!raw || raw === "") return "NO_ROW";
  return raw.replace(/\s+/g, " ").slice(0, 120);
};
const isFree = (state) => state === "NO_ROW" || state.includes('"state": "free"')
  || state.includes('"state":"free"');

const manifestId = await fetch(`${BASE}/api/v1/ontology/manifests/current`, {
  headers: HEADERS(1),
}).then((r) => r.json()).then((b) => b?.data?.manifestId);

async function dryRun(turn, dsl) {
  const response = await fetch(`${BASE}/api/v1/query/dry-run`, {
    method: "POST", headers: HEADERS(turn),
    body: JSON.stringify({ manifestId, ...dsl }),
  });
  return response.json();
}

const results = [];
const note = (id, name, pass, detail) => {
  results.push({ id, name, pass, detail });
  console.log(`${pass ? "PASS" : "FAIL"} [${id}] ${name} — ${JSON.stringify(detail).slice(0, 180)}`);
};

// T1 初始状态
const initial = stateOf();
note("T1", "初始会话无场景状态（free）", isFree(initial), { state: initial });

// T2 scope 外资产直传（1004000007）
const t2 = await dryRun(2, {
  mode: "QUERY", scenarioId: "scenario.sales", metricId: "metric.order_count",
  dimensions: ["dim.product_category"], timeExpressionRaw: "上个月",
  timeRange: { field: "dim.order_date" },
});
const s2 = stateOf();
note("T2", "scope 越界拒绝且不落 in_scenario", t2.code === 1004000007 && isFree(s2),
  { code: t2.code, state: s2 });

// T3 模式越界（1004000005）
const t3 = await dryRun(3, {
  mode: "RETENTION", scenarioId: "scenario.sales", timeExpressionRaw: "近 90 天",
  analysisParams: {
    cohortEvent: "ORDER_PLACED", returnEvent: "ORDER_PLACED",
    periodUnit: "WEEK", periods: 4, subjectPropertyId: "property.customer.customer_id",
  },
});
const s3 = stateOf();
note("T3", "模式越界拒绝且不落 in_scenario", t3.code === 1004000005 && isFree(s3),
  { code: t3.code, state: s3 });

// T4 DSL 校验失败（场景内非法维度）→ 业务拒绝，状态仍 free
const t4 = await dryRun(4, {
  mode: "QUERY", scenarioId: "scenario.sales", metricId: "metric.gmv",
  dimensions: ["dim.nonexistent_audit"], timeExpressionRaw: "上个月",
  timeRange: { field: "dim.order_date" },
});
const s4 = stateOf();
note("T4", "prepare 后续 DSL 校验失败不落 in_scenario",
  t4.code !== 0 && t4.code !== undefined && isFree(s4),
  { code: t4.code, state: s4 });

// T5 合法场景直传 → 成功，状态提交
const t5 = await dryRun(5, {
  mode: "QUERY", scenarioId: "scenario.sales", metricId: "metric.gmv",
  dimensions: ["dim.region"], timeExpressionRaw: "上个月",
  timeRange: { field: "dim.order_date" },
});
const s5 = stateOf();
note("T5", "全链成功后提交 in_scenario（正向路径未破坏）",
  t5.code === 0 && s5.includes("in_scenario"),
  { code: t5.code, state: s5 });

const passed = results.filter((r) => r.pass).length;
const evidence = {
  runId, ranAt: new Date().toISOString(), manifestId,
  scriptVersion: "scenario-state-failclosed-v1",
  fix: "M6 全量深度审核 D-1：adjudicate 纯前置校验 + commitExplicitRoute 在 prepare 全链成功后提交",
  results,
  conclusion: passed === results.length
    ? "失败即拒、目标状态不变（拒绝请求零状态污染）+ 正向提交生效"
    : `${passed}/${results.length} 通过`,
};
writeFileSync(OUT, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
console.log(`\nscenario-state: ${passed}/${results.length} → ${OUT}`);
process.exit(passed === results.length ? 0 : 1);
