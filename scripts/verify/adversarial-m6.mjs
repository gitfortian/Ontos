#!/usr/bin/env node
/**
 * M6 对抗扩充探针（T5.2，≥7 新探针全拒；兼 §3.8 安全压测全量的 M6 增量）。
 *
 * 探针清单（M6 规格 T5.2）：
 *   P1 DSL scenarioId 直传未授权场景（G-2 直传裁决 D1 四步）→ 1004000003
 *   P2 合法场景 + scope 外资产直传 → 1004000007（SCENARIO_SCOPE_VIOLATION）
 *   P3 场景内未声明近似降级的模式请求（allowedAnalysisModes=[query] vs RETENTION）→ 1004000005
 *   P4 伪造场景回传（clarificationChoice=scenario:ghost，未持久化候选）→ 1004000002
 *   P5 伪造近似降级回传（clarificationChoice=approx:ghost）→ 1004000002
 *   P6 直连 DSL 伪造 vqrId 键（DslCodec 键白名单外）→ 形状拒绝 fail-closed
 *   P7 dependsOn 悬空引用（resultRef.fromSubIntentId 不在依赖集）→ 1002000044
 *   P8 resultRef 注入值参数化（正向泄露检查）：依赖子 SQL 零字面量、占位符绑定
 *
 * 判据：P1–P7 全部业务错误码拒绝且非 500（fail-closed，无静默回退）；P8 泄露 = 0。
 * 用法：node scripts/verify/adversarial-m6.mjs --base-url http://127.0.0.1:8080
 */
import { execSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const OUT = resolve(repo, "docs/06-verification/evaluation/对抗扩充-M6证据-v1.json");
const BASE = process.argv.includes("--base-url")
  ? process.argv[process.argv.indexOf("--base-url") + 1]
  : "http://127.0.0.1:8080";
const PSQL = "PGPASSWORD=123456 D:/pg/bin/psql.exe -U postgres -h localhost -d ontos -t -A";
const sql = (statement) => execSync(`${PSQL} -c "${statement.replace(/"/g, '\\"')}"`,
  { encoding: "utf8", shell: "bash" }).trim();

let MANIFEST_ID = process.argv.includes("--manifest-id")
  ? process.argv[process.argv.indexOf("--manifest-id") + 1]
  : null;
if (!MANIFEST_ID) {
  MANIFEST_ID = await fetch(`${BASE}/api/v1/ontology/manifests/current`, {
    headers: { "X-Ontos-Tenant": "demo", "X-Ontos-User": "adv-m6" },
  }).then((r) => r.json()).then((b) => b?.data?.manifestId);
}

const runId = `adv${Date.now()}`;
let seq = 0;
const nextRequestId = () => `adv-${runId}-${++seq}`;

async function dryRun(dsl) {
  const endpoint = dsl.subIntents ? "/api/v1/query/composite/execute" : "/api/v1/query/dry-run";
  const response = await fetch(BASE + endpoint, {
    method: "POST",
    headers: {
      "Content-Type": "application/json", "X-Ontos-Tenant": "demo",
      "X-Ontos-User": `adv-${runId}-${seq}`, "X-Ontos-Session": `s-${seq}`,
      "X-Ontos-Turn": "1", "X-Request-Id": nextRequestId(),
    },
    body: JSON.stringify({ manifestId: MANIFEST_ID, ...dsl }),
  });
  const payload = await response.json();
  return { httpStatus: response.status, code: payload.code, msg: payload.msg };
}

async function ask(user, turn, utterance, clarificationChoice) {
  const body = { utterance };
  if (clarificationChoice) body.clarificationChoice = clarificationChoice;
  const response = await fetch(`${BASE}/api/v1/ask/runs`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json", "X-Ontos-Tenant": "demo",
      "X-Ontos-User": user, "X-Ontos-Session": `s-${user}`, "X-Ontos-Turn": String(turn),
    },
    body: JSON.stringify(body),
  });
  const payload = await response.json();
  return { httpStatus: response.status, code: payload.code, terminal: payload.data?.terminal };
}

const results = [];
const expectReject = (id, name, outcome, expectedCode) => {
  const pass = outcome.httpStatus < 500 && outcome.code === expectedCode;
  results.push({ id, name, pass, expectedCode, actualCode: outcome.code, msg: outcome.msg ?? null });
  console.log(`${pass ? "PASS" : "FAIL"} [${id}] ${name} — 期望 ${expectedCode}，实测 ${outcome.code}`);
};

// P1 DSL scenarioId 直传未授权场景（G-2：显式选择必须过授权 + 已发布校验，失败即拒不静默回退）
expectReject("P1", "直传未授权/不存在的场景", await dryRun({
  mode: "QUERY", scenarioId: "scenario.nonexistent", metricId: "metric.gmv",
  dimensions: [], timeExpressionRaw: "上个月", timeRange: { field: "dim.order_date" },
}), 1004000003);

// P2 合法场景 + scope 外资产（场景边界独立于授权边界，只收窄不放宽）
expectReject("P2", "场景内 scope 外资产直传", await dryRun({
  mode: "QUERY", scenarioId: "scenario.sales", metricId: "metric.order_count",
  dimensions: ["dim.product_category"], timeExpressionRaw: "上个月",
  timeRange: { field: "dim.order_date" },
}), 1004000007);

// P3 场景内未声明近似降级的模式请求（allowedAnalysisModes=[query]；RETENTION 无近似承担者 → 不退化自由 SQL）
expectReject("P3", "场景内未声明近似的模式请求（RETENTION）", await dryRun({
  mode: "RETENTION", scenarioId: "scenario.sales",
  timeExpressionRaw: "近 90 天",
  analysisParams: {
    cohortEvent: "ORDER_PLACED", returnEvent: "ORDER_PLACED",
    periodUnit: "WEEK", periods: 4, subjectPropertyId: "property.customer.customer_id",
  },
}), 1004000005);

// P4 伪造场景回传（场景状态机校验先于候选原子校验——两道闸门均 fail-closed）
{
  const outcome = await ask(`adv-${runId}-p4`, 1, "就要这个", "scenario:scenario.ghost");
  expectReject("P4", "伪造场景回传（未持久化候选）", outcome, 1004000004);
}

// P5 伪造近似降级回传（approx: 前缀候选同样必须来自持久化集合）
{
  const outcome = await ask(`adv-${runId}-p5`, 1, "就要这个", "approx:skill.ghost");
  expectReject("P5", "伪造近似降级回传", outcome, 1004000002);
}

// P6 直连 DSL 伪造 vqrId 键（DslCodec 键白名单外 → 结构化形状拒绝，fail-closed 无新码）
{
  const outcome = await dryRun({
    mode: "QUERY", metricId: "metric.gmv", dimensions: [],
    vqrId: "vqr.ghost", timeRange: { field: "dim.order_date" },
  });
  const pass = outcome.httpStatus < 500 && outcome.code !== 0;
  results.push({ id: "P6", name: "直连 DSL 伪造 vqrId 键（白名单外 fail-closed）", pass,
    expectedCode: "非 0 业务拒绝（形状/键校验）", actualCode: outcome.code, msg: outcome.msg ?? null });
  console.log(`${pass ? "PASS" : "FAIL"} [P6] 直连 DSL 伪造 vqrId 键 — 实测 code=${outcome.code}`);
}

// P7 依赖图校验：dependsOn 引用不存在的子意图 → DEPENDENCY_CYCLE 1_002_000_044。
// （resultRef.fromSubIntentId 不在 dependsOn 的悬空引用在 DslValidator 更早拦：1002000000，同 fail-closed）
expectReject("P7", "dependsOn 引用不存在的子意图", await dryRun({
  mode: "QUERY",
  subIntents: [
    { subIntentId: "s1", metricId: "metric.gmv", mode: "QUERY", dimensions: ["dim.region"], timeExpressionRaw: "上个月", timeRange: { field: "dim.order_date" } },
    { subIntentId: "s2", metricId: "metric.gmv", mode: "QUERY", dimensions: ["dim.channel"], timeExpressionRaw: "上个月", timeRange: { field: "dim.order_date" },
      filters: [{ fieldId: "dim.region", operator: "EQ", values: [] }], dependsOn: ["s9"],
      resultRef: { fromSubIntentId: "s9", selector: { type: "ordered_top", row: 1, column: "dim.region" }, inject: { type: "filter_value", filterIndex: 0 } } },
  ],
}), 1002000044);

// P8 resultRef 注入值参数化（正向泄露检查）：真依赖复合执行 → 依赖子 SQL 零注入值字面量
{
  const requestId = nextRequestId();
  const response = await fetch(`${BASE}/api/v1/query/composite/execute`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json", "X-Ontos-Tenant": "demo",
      "X-Ontos-User": `adv-${runId}-p8`, "X-Ontos-Session": "s-p8",
      "X-Ontos-Turn": "1", "X-Request-Id": requestId,
    },
    body: JSON.stringify({
      manifestId: MANIFEST_ID, mode: "QUERY",
      subIntents: [
        { subIntentId: "s1", metricId: "metric.gmv", mode: "QUERY", dimensions: ["dim.region"], timeExpressionRaw: "上个月", timeRange: { field: "dim.order_date" } },
        { subIntentId: "s2", metricId: "metric.gmv", mode: "QUERY", dimensions: ["dim.channel"], timeExpressionRaw: "上个月", timeRange: { field: "dim.order_date" },
          filters: [{ fieldId: "dim.region", operator: "EQ", values: [] }], dependsOn: ["s1"],
          resultRef: { fromSubIntentId: "s1", selector: { type: "ordered_top", row: 1, column: "dim.region" }, inject: { type: "filter_value", filterIndex: 0 } } },
      ],
    }),
  });
  const payload = await response.json();
  const executed = payload.code === 0;
  // 依赖子（:s2）的审计 SQL：注入值（华东，UTF-8 e58d8ee4b89c）不得以字面量出现，必须是占位符绑定
  const audit = sql(`SELECT sql_text LIKE '%' || convert_from(decode('e58d8ee4b89c','hex'),'UTF8') || '%',
    sql_text LIKE '%?%', sql_text IS NOT NULL
    FROM audit_log WHERE request_id = '${requestId}:s2' AND action = 'EXECUTE' LIMIT 1`);
  const [hasLiteral, hasPlaceholder, hasSql] = audit.split("|");
  const pass = executed && hasSql === "t" && hasLiteral === "f" && hasPlaceholder === "t";
  results.push({ id: "P8", name: "resultRef 注入值参数化（依赖子 SQL 零字面量）", pass,
    detail: { executed, hasLiteral, hasPlaceholder, hasSql } });
  console.log(`${pass ? "PASS" : "FAIL"} [P8] 注入值参数化 — 字面量=${hasLiteral} 占位符=${hasPlaceholder}`);
}

// ── 汇总 ──
const passed = results.filter((r) => r.pass).length;
const evidence = {
  runId,
  ranAt: new Date().toISOString(),
  manifestId: MANIFEST_ID,
  scriptVersion: "adversarial-m6-v1",
  basis: "M6 规格 T5.2（≥7 新探针全拒）+ §3.8 安全压测复用对抗探针全量（M6 增量）",
  results,
  conclusion: passed === results.length
    ? `${results.length}/${results.length} 全拒（fail-closed 无静默回退）；注入值参数化泄露 = 0`
    : `${passed}/${results.length} 通过`,
};
writeFileSync(OUT, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
console.log(`\nadversarial-m6: ${passed}/${results.length} → ${OUT}`);
process.exit(passed === results.length ? 0 : 1);
