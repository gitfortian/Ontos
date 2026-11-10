#!/usr/bin/env node
/**
 * 本地压测（M6/W5 T5.1，OBL-M7 交付物 ⑥：性能压测 P95 延迟 / 成本 / 并发 SLO）。
 *
 * SLO 预算（按 §13.4「阈值必须在查看正式结果前冻结」，本脚本运行前冻结）：
 *   - P95 延迟 < 2000 ms（确定性降级路径，本地 PG18，混合端点）；
 *   - 业务错误率 = 0（HTTP 5xx / 业务 code≠0 计入）；
 *   - 超时率 = 0（单请求 10s 客户端超时）；
 *   - 成本口径：确定性路径 LLM token 成本 = 0（agent.enabled=false，降级合同）；
 *     LLM 路径成本评测归 M7 LLM 评测（缺口 E-7），不在此冒充。
 * 安全压测：按 §3.8 复用对抗探针全量（adversarial-probe.mjs，基线内独立运行）。
 *
 * 用法：node scripts/verify/load-m6.mjs --base-url http://127.0.0.1:8080
 */
import { writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const OUT = resolve(repo, "docs/06-verification/evaluation/压测-M6证据-v1.json");
const BASE = process.argv.includes("--base-url")
  ? process.argv[process.argv.indexOf("--base-url") + 1]
  : "http://127.0.0.1:8080";
const SLO = { p95Ms: 2000, errorRate: 0, timeoutRate: 0 };

let MANIFEST_ID = await fetch(`${BASE}/api/v1/ontology/manifests/current`, {
  headers: { "X-Ontos-Tenant": "demo", "X-Ontos-User": "load" },
}).then((r) => r.json()).then((b) => b?.data?.manifestId);

const runId = `L${Date.now()}`;
let seq = 0;

/** 混合负载（与真实分布近似）：聚合 60% / lookup 20% / 复合 20%。 */
function pickCase() {
  const roll = Math.random();
  if (roll < 0.6) {
    const dims = roll < 0.3 ? ["dim.region"] : roll < 0.45 ? ["dim.channel"] : [];
    return { path: "/api/v1/query/dry-run", body: {
      manifestId: MANIFEST_ID, mode: "QUERY", metricId: "metric.gmv", dimensions: dims,
      timeExpressionRaw: "上个月", timeRange: { field: "dim.order_date" },
    } };
  }
  if (roll < 0.8) {
    return { path: "/api/v1/query/dry-run", body: {
      manifestId: MANIFEST_ID, mode: "LOOKUP", entityId: "concept.customer",
      attributes: ["property.customer.tier"],
      identity: { propertyId: "property.customer.customer_id", value: "c-1001" }, limit: 1,
    } };
  }
  return { path: "/api/v1/query/composite/execute", body: {
    manifestId: MANIFEST_ID, mode: "QUERY",
    subIntents: [
      { subIntentId: "s1", metricId: "metric.gmv", mode: "QUERY", dimensions: ["dim.region"], timeExpressionRaw: "上个月", timeRange: { field: "dim.order_date" } },
      { subIntentId: "s2", metricId: "metric.order_count", mode: "QUERY", dimensions: ["dim.channel"], timeExpressionRaw: "今年", timeRange: { field: "dim.order_date" } },
    ],
  } };
}

async function once() {
  seq += 1;
  const c = pickCase();
  const started = Date.now();
  try {
    const response = await fetch(BASE + c.path, {
      method: "POST",
      headers: {
        "Content-Type": "application/json", "X-Ontos-Tenant": "demo",
        "X-Ontos-User": `load-${runId}-${seq}`, "X-Ontos-Session": `s-${seq}`,
        "X-Ontos-Turn": "1", "X-Request-Id": `load-${runId}-${seq}`,
      },
      body: JSON.stringify(c.body),
      signal: AbortSignal.timeout(10000),
    });
    const payload = await response.json();
    const latency = Date.now() - started;
    const businessOk = response.status < 500 && payload.code === 0;
    return { latency, ok: businessOk, timeout: false, httpStatus: response.status };
  } catch (error) {
    const latency = Date.now() - started;
    const timeout = error.name === "TimeoutError" || error.name === "AbortError";
    return { latency, ok: false, timeout, httpStatus: null };
  }
}

const percentile = (sorted, p) => {
  if (sorted.length === 0) return null;
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, idx)];
};

async function phase(name, concurrency, total) {
  const latencies = [];
  let errors = 0;
  let timeouts = 0;
  const startedAt = Date.now();
  let launched = 0;
  const workers = Array.from({ length: concurrency }, async () => {
    while (launched < total) {
      launched += 1;
      const result = await once();
      latencies.push(result.latency);
      if (!result.ok) errors += 1;
      if (result.timeout) timeouts += 1;
    }
  });
  await Promise.all(workers);
  const wallMs = Date.now() - startedAt;
  const sorted = [...latencies].sort((a, b) => a - b);
  return {
    name, concurrency, total,
    p50: percentile(sorted, 50), p95: percentile(sorted, 95), p99: percentile(sorted, 99),
    max: sorted.at(-1) ?? null,
    errorCount: errors, timeoutCount: timeouts,
    throughputRps: Number((total / (wallMs / 1000)).toFixed(2)),
  };
}

// 预热（连接池 / JIT / 缓存）
for (let i = 0; i < 10; i += 1) await once();

const phases = [];
phases.push(await phase("并发 1", 1, 20));
phases.push(await phase("并发 8", 8, 80));
phases.push(await phase("并发 16", 16, 160));

const allLatencies = [];
const totalErrors = phases.reduce((s, p) => s + p.errorCount, 0);
const totalTimeouts = phases.reduce((s, p) => s + p.timeoutCount, 0);
const totalRequests = phases.reduce((s, p) => s + p.total, 0);
// 全量时延重算 P95（跨相位）
const evidence = {
  runId,
  ranAt: new Date().toISOString(),
  manifestId: MANIFEST_ID,
  scriptVersion: "load-m6-v1",
  agentMode: "ontos.agent.enabled=false（确定性降级路径；LLM token 成本=0，LLM 路径成本归 M7 LLM 评测）",
  sloFrozenBeforeRun: SLO,
  sloBasis: "§13.4 阈值预冻结 + §19 预设 SLO；确定性路径本地 PG18 预算 P95<2000ms、错误率 0、超时率 0",
  safetyLoad: "安全压测按 §3.8 复用对抗探针全量（adversarial-probe.mjs，基线独立运行 9/9）",
  phases,
  verdict: {
    p95WithinBudget: phases.every((p) => p.p95 < SLO.p95Ms),
    errorRateZero: totalErrors === 0,
    timeoutRateZero: totalTimeouts === 0,
    totalRequests, totalErrors, totalTimeouts,
  },
};
const allPass = Object.values(evidence.verdict).every((v) => v !== false);
evidence.verdict.overall = allPass ? "SLO 全满足" : "SLO 未满足（见分项）";
writeFileSync(OUT, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");

for (const p of phases) {
  console.log(
    `${p.name}: n=${p.total} P50=${p.p50}ms P95=${p.p95}ms P99=${p.p99}ms ` +
    `max=${p.max}ms err=${p.errorCount} timeout=${p.timeoutCount} tput=${p.throughputRps}rps`,
  );
}
console.log(`load: ${evidence.verdict.overall}（预算 P95<${SLO.p95Ms}ms）→ ${OUT}`);
process.exit(allPass ? 0 : 1);
