#!/usr/bin/env node
/**
 * PoC #9 追踪验证（M6/W5 T5.1，OBL-M7 交付物 ⑤：问题 → DSL → SQL → 结果 全链关联）。
 *
 * 口径（M6 规格 §3.8）：不自研埋点——复用既有 X-Request-Id/MDC traceId + 审计留痕
 * （audit_log 的 dsl / sql_text / row_count 列）+ ask run 事件重放（AgentScope 事件语义）+
 * Actuator 指标端点；验证「一次请求的四要素可用唯一 request_id 串起」。
 *
 * 验证项：
 *   T1 dry-run 链：X-Request-Id 唯一 → 审计行（dsl + sql_text + row_count）同 request_id 关联
 *      → 响应回显 X-Request-Id（traceId 贯穿）
 *   T2 ask 编排链：utterance（问题）→ run 事件流（RUN_STARTED.input … RUN_FINISHED）
 *      → 审计行（trace_id 关联）→ 会话投影落库
 *   T3 观测面：/actuator/metrics 可达（运行时指标暴露）
 *
 * 用法：node scripts/verify/poc09-trace.mjs --base-url http://127.0.0.1:8080
 */
import { execSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const OUT = resolve(repo, "docs/06-verification/evaluation/PoC9-追踪证据-v1.json");
const BASE = process.argv.includes("--base-url")
  ? process.argv[process.argv.indexOf("--base-url") + 1]
  : "http://127.0.0.1:8080";
const PSQL = "PGPASSWORD=123456 D:/pg/bin/psql.exe -U postgres -h localhost -d ontos -t -A";
const sql = (statement) => execSync(`${PSQL} -c "${statement.replace(/"/g, '\\"')}"`,
  { encoding: "utf8", shell: "bash" }).trim();

let MANIFEST_ID = await fetch(`${BASE}/api/v1/ontology/manifests/current`, {
  headers: { "X-Ontos-Tenant": "demo", "X-Ontos-User": "poc9" },
}).then((r) => r.json()).then((b) => b?.data?.manifestId);

const results = [];
const note = (id, name, pass, detail) => {
  results.push({ id, name, pass, detail });
  console.log(`${pass ? "PASS" : "FAIL"} [${id}] ${name}${detail ? " — " + JSON.stringify(detail).slice(0, 160) : ""}`);
};

const runId = `p9${Date.now()}`;

// ── T1 dry-run 链：问题(DSL) → 编译 → SQL → 结果行，同 request_id 关联 ──
{
  const requestId = `poc9-dry-${runId}`;
  const response = await fetch(`${BASE}/api/v1/query/dry-run`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json", "X-Ontos-Tenant": "demo",
      "X-Ontos-User": `poc9-${runId}`, "X-Ontos-Session": `s-${runId}`,
      "X-Ontos-Turn": "1", "X-Request-Id": requestId,
    },
    body: JSON.stringify({
      manifestId: MANIFEST_ID, mode: "QUERY", metricId: "metric.gmv",
      dimensions: ["dim.region"], timeExpressionRaw: "上个月",
      timeRange: { field: "dim.order_date" },
    }),
  });
  const echoed = response.headers.get("X-Request-Id");
  const payload = await response.json();
  const ok = payload.code === 0;
  note("T1-1", "dry-run 执行成功（DSL → 编译 → SQL → rows）", ok,
    { code: payload.code, rows: payload.data?.rows?.length });
  note("T1-2", "响应回显 X-Request-Id（traceId 贯穿响应）", echoed === requestId,
    { echoed, requestId });

  const audit = sql(`SELECT dsl IS NOT NULL, sql_text IS NOT NULL, row_count IS NOT NULL,
    trace_id = '${requestId}', request_id = '${requestId}'
    FROM audit_log WHERE request_id = '${requestId}' AND action IN ('EXECUTE','DRY_RUN')
    ORDER BY created_at DESC LIMIT 1`);
  const parts = audit.split("|");
  note("T1-3", "审计行五要素同 request_id 落库（dsl / sql_text / row_count / trace / request 关联）",
    parts.length === 5 && parts.every((p) => p === "t"), { audit });
  const dslHit = sql(`SELECT position('metric.gmv' in dsl::text) > 0 FROM audit_log
    WHERE request_id = '${requestId}' AND action IN ('EXECUTE','DRY_RUN') ORDER BY created_at DESC LIMIT 1`);
  note("T1-4", "审计 dsl 列含结构化 DSL（metricId 全文可检索）", dslHit === "t",
    { dslHit });
}

// ── T2 ask 编排链：问题 → 事件流 → 审计 → 会话投影 ──
{
  const user = `poc9-ask-${runId}`;
  const requestId = `poc9-ask-${runId}`;
  const askResponse = await fetch(`${BASE}/api/v1/ask/runs`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json", "X-Ontos-Tenant": "demo",
      "X-Ontos-User": user, "X-Ontos-Session": `s-${runId}`, "X-Ontos-Turn": "1",
    },
    body: JSON.stringify({ utterance: "上个月各区域的成交金额" }),
  });
  const payload = await askResponse.json();
  note("T2-1", "ask 编排执行成功（query 终态）",
    payload.code === 0 && payload.data?.terminal === "query",
    { code: payload.code, terminal: payload.data?.terminal });

  const runRow = sql(`SELECT run_id::text FROM ask_run WHERE tenant_id='demo' AND user_id='${user}'
    ORDER BY created_at DESC LIMIT 1`);
  const events = await fetch(`${BASE}/api/v1/ask/run/${runRow}/events?afterSeq=0`, {
    headers: { "X-Ontos-Tenant": "demo", "X-Ontos-User": user,
      "X-Ontos-Session": `s-${runId}`, "X-Ontos-Turn": "1" },
  }).then((r) => r.json());
  const types = (events.data ?? []).map((e) => e.type);
  note("T2-2", "run 事件流可重放（RUN_STARTED → … → RUN_FINISHED）",
    events.code === 0 && types.includes("RUN_STARTED") && types.includes("RUN_FINISHED"),
    { types });

  const started = (events.data ?? []).find((e) => e.type === "RUN_STARTED");
  const input = started ? JSON.parse(started.payload)?.input ?? {} : {};
  note("T2-3", "RUN_STARTED.input 携带原始问题（utterance，脱敏后身份字段抹除）",
    typeof input.utterance === "string" && input.utterance.includes("成交金额"),
    { utterance: input.utterance });

  const auditCount = sql(`SELECT count(*) FROM audit_log WHERE tenant_id='demo' AND user_id='${user}'
    AND action='EXECUTE'`);
  const auditTrace = sql(`SELECT trace_id IS NOT NULL AND dsl IS NOT NULL FROM audit_log
    WHERE tenant_id='demo' AND user_id='${user}' AND action='EXECUTE' LIMIT 1`);
  note("T2-4", "编排链审计留痕（trace_id + dsl 同行关联）",
    Number(auditCount) >= 1 && auditTrace === "t", { auditCount });

  const projection = sql(`SELECT position('metric.gmv' in resolved_entities::text) > 0
    FROM session_state WHERE tenant_id='demo' AND user_id='${user}' AND session_id='s-${runId}'`);
  note("T2-5", "会话投影落库（resolved_entities 含命中资产——跨轮追踪锚点）",
    projection === "t", { projection });
}

// ── T3 观测面：Actuator 指标 ──
{
  const metrics = await fetch(`${BASE}/actuator/metrics`, {
    headers: { "X-Ontos-Tenant": "demo", "X-Ontos-User": "poc9" },
  }).then((r) => r.json());
  const names = metrics.names ?? [];
  note("T3-1", "/actuator/metrics 可达（运行时指标暴露）",
    Array.isArray(names) && names.length > 0, { metricCount: names.length });
  note("T3-2", "JVM/HTTP 基础指标在位", names.includes("jvm.memory.used") && names.some((n) => n.startsWith("http")),
    { hasJvm: names.includes("jvm.memory.used") });
}

// ── 结论固化 ──
const passed = results.filter((r) => r.pass).length;
const evidence = {
  runId,
  ranAt: new Date().toISOString(),
  manifestId: MANIFEST_ID,
  scriptVersion: "poc09-trace-v1",
  chain: "问题(utterance/DSL) → [X-Request-Id/MDC traceId] → 编译 DSL(audit.dsl) → SQL(audit.sql_text) → 结果(audit.row_count / 事件流重放)",
  infra: "复用既有机制（不自研埋点）：OntosRequestContextFilter traceId + audit_log 四要素 + ask_run_event 重放 + Actuator",
  results,
  conclusion: passed === results.length
    ? "追踪链路四要素全关联：一次请求的 问题 → DSL → SQL → 结果 可用唯一 request_id 串起并事后取证"
    : `${passed}/${results.length} 通过，存在断链`,
};
writeFileSync(OUT, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
console.log(`\npoc09-trace: ${passed}/${results.length} 通过 → ${OUT}`);
process.exit(passed === results.length ? 0 : 1);
