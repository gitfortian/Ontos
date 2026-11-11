#!/usr/bin/env node
/**
 * LLM 路径评测 runner（M7/W2/T2.2，E-3/E-7）。
 *
 * 与确定性 runner 的区别（规格 R-9）：**不复用 buildDsl**——本脚本只发自然语言，
 * DSL 由服务端组装，判据从「审计留痕的 DSL + 终态」反读，不注入任何期望值。
 *
 * 判据（逐题，全部满足才算 pass）：
 *   1. 终态 terminal 与期望一致；
 *   2. 若期望给出 mode / metricId / dimensions / timeExpressionRaw，则取该题审计行里的 DSL 逐项比对
 *      （终态非 query/composite 时无 DSL，只判终态）；
 *   3. 「是否真的走了 LLM」由 llm_call_trace 是否有该 requestId 的记录判定（不靠猜测）。
 *
 * 统计：总体成功率 + Wilson95、按 analysis_mode / query_shape / scenario_entry_mode 分层、
 * 每题 token 与耗时、fallback（无 LLM 记录）计数。阈值引用 ADR-0005，不在此脚本内改。
 *
 * 用法：
 *   node scripts/verify/llm-test-runner.mjs --base-url http://127.0.0.1:8080 \
 *     --set docs/06-verification/evaluation/test集-v5.json --runs 3 --concurrency 4 \
 *     --out docs/06-verification/evaluation/LLM路径评测证据-v1.json
 *   # 试点：--limit 10 --runs 1 --out target/llm-pilot.json
 */
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const argOf = (name, fallback) => {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : fallback;
};
const BASE = argOf("--base-url", "http://127.0.0.1:8080");
const SET_PATH = resolve(repo, argOf("--set", "docs/06-verification/evaluation/test集-v5.json"));
const OUT = resolve(repo, argOf("--out", "docs/06-verification/evaluation/LLM路径评测证据-v1.json"));
const RUNS = Number(argOf("--runs", "3"));
const CONCURRENCY = Number(argOf("--concurrency", "4"));
const LIMIT = Number(argOf("--limit", "0"));
const ONLY = argOf("--only", "");
const PSQL_BIN = process.env.ONTOS_PSQL_BIN || "D:/pg/bin/psql.exe";
const sql = (statement) => execFileSync(PSQL_BIN, ["-U", "postgres", "-h", "localhost", "-d", "ontos", "-t", "-A", "-c", statement],
  { encoding: "utf8", env: { ...process.env, PGPASSWORD: process.env.ONTOS_PGPASSWORD || "123456" } }).trim();

const SET = JSON.parse(readFileSync(SET_PATH, "utf8"));
const CASES = (SET.cases ?? [])
  .filter((item) => !item.skipReason)
  .filter((item) => (ONLY ? String(item.id).includes(ONLY) : true))
  .slice(0, LIMIT > 0 ? LIMIT : undefined);

const RUN_ID = argOf("--run-id", `L${Date.now()}`);
const requestIdOf = (runIndex, caseId, turnIndex) => `llml-${RUN_ID}-r${runIndex}-${caseId}-t${turnIndex}`;


/** 从审计表批量取本题的 DSL（只取有 dsl 的行，EXECUTE 优先）。 */
function loadDsl(requestIds) {
  const map = new Map();
  if (requestIds.length === 0) {
    return map;
  }
  const list = requestIds.map((id) => `'${id.replace(/'/g, "''")}'`).join(",");
  const rows = sql(`select request_id, action, coalesce(dsl::text,'') from audit_log `
    + `where tenant_id='demo' and request_id in (${list}) order by id`);
  if (!rows) {
    return map;
  }
  for (const line of rows.split("\n")) {
    const [requestId, action, dslText] = line.split("|");
    if (!dslText) {
      continue;
    }
    if (!map.has(requestId) || action === "EXECUTE") {
      try {
        map.set(requestId, JSON.parse(dslText));
      } catch {
        // 非法 JSON 忽略（审计侧不阻断评测，按无 DSL 处理）
      }
    }
  }
  return map;
}

/** 从追踪表取每个 requestId 的模型调用次数与 token（判定是否真走 LLM + 成本）。 */
function loadLlmUsage(requestIds) {
  const map = new Map();
  if (requestIds.length === 0) {
    return map;
  }
  const list = requestIds.map((id) => `'${id.replace(/'/g, "''")}'`).join(",");
  const rows = sql(`select trace_id, count(*), coalesce(sum(total_tokens),0), `
    + `coalesce(sum(latency_ms),0) from llm_call_trace where tenant_id='demo' `
    + `and trace_id in (${list}) group by trace_id`);
  if (!rows) {
    return map;
  }
  for (const line of rows.split("\n")) {
    const [traceId, calls, tokens, latency] = line.split("|");
    map.set(traceId, { calls: Number(calls), tokens: Number(tokens), latencyMs: Number(latency) });
  }
  return map;
}

function percentile(sorted, p) {
  if (sorted.length === 0) {
    return null;
  }
  const index = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, index)];
}

/** Wilson 95% 区间（与评测统计口径 v1 一致）。 */
function wilson(pass, total) {
  if (total === 0) {
    return null;
  }
  const z = 1.96;
  const p = pass / total;
  const denominator = 1 + (z * z) / total;
  const center = (p + (z * z) / (2 * total)) / denominator;
  const margin = (z * Math.sqrt((p * (1 - p)) / total + (z * z) / (4 * total * total))) / denominator;
  return [Number(Math.max(0, center - margin).toFixed(4)), Number(Math.min(1, center + margin).toFixed(4))];
}

/** 多轮题（turns 数组）按同一会话逐轮发送；单轮题即一问。 */
const turnsOf = (caseItem) => (Array.isArray(caseItem.turns) && caseItem.turns.length
  ? caseItem.turns : [caseItem.question]);

async function askOnce(caseItem, runIndex) {
  const sessionId = `s-${requestIdOf(runIndex, caseItem.id, 1)}`;
  const turns = turnsOf(caseItem);
  let last = null;
  const turnTerminals = [];
  for (let index = 0; index < turns.length; index += 1) {
    const requestId = requestIdOf(runIndex, caseItem.id, index + 1);
    const started = Date.now();
    try {
      const response = await fetch(`${BASE}/api/v1/ask/runs`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Ontos-Tenant": "demo",
          "X-Ontos-User": `llm-eval-${RUN_ID}`,
          "X-Ontos-Session": sessionId,
          "X-Ontos-Turn": String(index + 1),
          "X-Request-Id": requestId,
        },
        body: JSON.stringify({ utterance: turns[index] }),
        signal: AbortSignal.timeout(180000),
      });
      const payload = await response.json();
      const data = payload.data ?? {};
      // 30s 同步等待超时返回 waiting 中间态：轮询 run 事件直到 RUN_FINISHED（与对抗探针同口径）
      if (data.terminal === "waiting") {
        const runId = data.runId ?? data.body?.runId ?? null;
        if (runId) {
          for (let attempt = 0; attempt < 30; attempt += 1) {
            await new Promise((done) => setTimeout(done, 5000));
            const events = await fetch(`${BASE}/api/v1/ask/run/${runId}/events?afterSeq=0`, {
              headers: {
                "X-Ontos-Tenant": "demo",
                "X-Ontos-User": `llm-eval-${RUN_ID}`,
              },
            }).then((item) => item.json()).catch(() => null);
            const finished = (events?.data ?? []).find((event) => event.type === "RUN_FINISHED");
            if (finished) {
              let finishedTerminal = null;
              try {
                finishedTerminal = JSON.parse(finished.payload ?? "{}").terminal ?? null;
              } catch {
                finishedTerminal = null;
              }
              data.terminal = finishedTerminal;
              break;
            }
          }
        }
      }
      last = {
        requestId,
        caseId: caseItem.id,
        runIndex,
        latencyMs: Date.now() - started,
        code: payload.code,
        terminal: data.terminal ?? null,
        degraded: data.degraded === true,
        rowCount: data.body?.rowCount ?? null,
        errorMessage: payload.code === 0 ? null : (payload.msg ?? "").slice(0, 200),
        turnIndex: index + 1,
      };
      turnTerminals.push(last.terminal);
    } catch (error) {
      last = {
        requestId,
        caseId: caseItem.id,
        runIndex,
        latencyMs: Date.now() - started,
        code: null,
        terminal: null,
        degraded: false,
        rowCount: null,
        errorMessage: `请求失败: ${error.message}`.slice(0, 200),
        turnIndex: index + 1,
      };
      turnTerminals.push(null);
    }
  }
  return { ...last, turnCount: turns.length, turnTerminals };
}

function judge(caseItem, result, dsl) {
  const expected = caseItem.expected ?? {};
  // 预期拒绝（out_of_capability）在 ask 路径有两种合法形态：
  //   a) terminal=out_of_capability（code=0）；b) 服务端护栏/校验直接拒绝（业务错误码 ≠0，如粒度扩散 1002000014）。
  // 只认业务错误码（Ontos 码为 10 位 1_0xx_xxx_xxx）；code=null（网络/超时）与 HTTP 5xx 不算预期拒绝。
  // 命中 b) 时跳过全部 DSL 判定（被拒绝的请求没有可对账的 DSL）。
  const businessRejected = expected.terminal === "out_of_capability"
    && typeof result.code === "number" && result.code >= 1_000_000_000;
  if (businessRejected) {
    return {
      pass: true,
      reasons: [],
      rejectedByGuardrail: result.code,
      rejectionMessage: result.errorMessage,
    };
  }
  const terminalOk = !expected.terminal || expected.terminal === result.terminal;
  const needsDsl = ["query", "composite"].includes(expected.terminal ?? "");
  const modeOk = !needsDsl || !expected.mode
    || String(expected.mode).toUpperCase() === String(dsl?.mode ?? "").toUpperCase();
  const metricOk = !needsDsl || !expected.metricId || expected.metricId === dsl?.metricId;
  const dimensions = (dsl?.dimensions ?? []).map(String);
  const dimsOk = !needsDsl || !Array.isArray(expected.dimensions)
    || expected.dimensions.every((dimension) => dimensions.includes(dimension));
  const timeOk = !needsDsl || !expected.timeExpressionRaw
    || expected.timeExpressionRaw === dsl?.timeExpressionRaw;
  // v2 判据新增：filters 与 LOOKUP 的 attributes / identity（此前漏判会让「缺过滤条件」的错答算对）
  const dslFilters = (dsl?.filters ?? []).map((item) => ({
    fieldId: item.fieldId ?? item.field_id,
    values: (item.values ?? []).map(String),
  }));
  const filtersOk = !needsDsl || !Array.isArray(expected.filters) || expected.filters.length === 0
    || expected.filters.every((expectedFilter) => {
      const match = dslFilters.find((item) => item.fieldId === expectedFilter.fieldId);
      return Boolean(match)
        && (expectedFilter.values ?? []).every((value) => match.values.includes(String(value)));
    });
  const attributes = (dsl?.attributes ?? []).map(String);
  const attributesOk = !expected.attributes || !Array.isArray(expected.attributes)
    || expected.attributes.every((attribute) => attributes.includes(attribute));
  const identityOk = !expected.identity
    || String(dsl?.identity?.value ?? "") === String(expected.identity.value ?? "");
  const reasons = [];
  if (!terminalOk) reasons.push(`terminal:${result.terminal}≠${expected.terminal}`);
  if (!modeOk) reasons.push(`mode:${dsl?.mode}≠${expected.mode}`);
  if (!metricOk) reasons.push(`metric:${dsl?.metricId}≠${expected.metricId}`);
  if (!dimsOk) reasons.push(`dims:${dimensions.join(",")}≠${(expected.dimensions ?? []).join(",")}`);
  if (!timeOk) reasons.push(`time:${dsl?.timeExpressionRaw}≠${expected.timeExpressionRaw}`);
  if (!filtersOk) reasons.push(`filters:${JSON.stringify(dslFilters)}≠${JSON.stringify(expected.filters ?? [])}`);
  if (!attributesOk) reasons.push(`attributes:${attributes.join(",")}≠${(expected.attributes ?? []).join(",")}`);
  if (!identityOk) reasons.push(`identity:${dsl?.identity?.value}≠${expected.identity?.value}`);
  return { pass: reasons.length === 0, reasons };
}

const results = [];
let cursor = 0;
async function worker() {
  while (cursor < CASES.length * RUNS) {
    const index = cursor;
    cursor += 1;
    const runIndex = Math.floor(index / CASES.length) + 1;
    const caseItem = CASES[index % CASES.length];
    results.push(await askOnce(caseItem, runIndex));
    if (results.length % 20 === 0) {
      console.log(`  ... ${results.length}/${CASES.length * RUNS} 已完成`);
    }
  }
}
await Promise.all(Array.from({ length: Math.max(1, CONCURRENCY) }, worker));

const requestIds = results.map((item) => item.requestId);
const dslMap = loadDsl(requestIds);
const usageMap = loadLlmUsage(requestIds);

const caseById = new Map(CASES.map((item) => [item.id, item]));
const rows = results.map((result) => {
  const caseItem = caseById.get(result.caseId);
  const dsl = dslMap.get(result.requestId) ?? null;
  const usage = usageMap.get(result.requestId) ?? { calls: 0, tokens: 0, latencyMs: 0 };
  const judgement = judge(caseItem, result, dsl);
  return {
    ...result,
    mode: dsl?.mode ?? null,
    metricId: dsl?.metricId ?? null,
    dimensions: dsl?.dimensions ?? [],
    timeExpressionRaw: dsl?.timeExpressionRaw ?? null,
    filters: (dsl?.filters ?? []).map((item) => ({
      fieldId: item.fieldId ?? item.field_id,
      values: (item.values ?? []).map(String),
    })),
    attributes: (dsl?.attributes ?? []).map(String),
    identityValue: dsl?.identity?.value ?? null,
    llmCalls: usage.calls,
    tokens: usage.tokens,
    llmUsed: usage.calls > 0,
    pass: judgement.pass,
    rejectedByGuardrail: judgement.rejectedByGuardrail ?? null,
    failReasons: judgement.reasons,
    layers: {
      analysis_mode: caseItem.analysis_mode,
      query_shape: caseItem.query_shape,
      scenario_entry_mode: caseItem.scenario_entry_mode,
      risk_type: caseItem.risk_type,
    },
  };
});

const summarize = (items) => {
  const total = items.length;
  const passed = items.filter((item) => item.pass).length;
  return { total, passed, rate: total ? Number((passed / total).toFixed(4)) : null, wilson95: wilson(passed, total) };
};

const byLayer = (key) => {
  const groups = new Map();
  for (const row of rows) {
    const value = row.layers[key] ?? "unknown";
    if (!groups.has(value)) {
      groups.set(value, []);
    }
    groups.get(value).push(row);
  }
  return Object.fromEntries([...groups.entries()].map(([value, items]) => [value, summarize(items)]));
};

const latencies = rows.map((item) => item.latencyMs).sort((left, right) => left - right);
const failures = rows.filter((item) => !item.pass);
const evidence = {
  task: "M7/W2/T2.2",
  runId: RUN_ID,
  ranAt: new Date().toISOString(),
  serviceBaseUrl: BASE,
  testSet: { path: SET_PATH.replace(`${repo}/`, ""), version: SET.version, total: SET.total, evaluated: CASES.length },
  runs: RUNS,
  judgement: {
    version: "v2（新增 filters / LOOKUP attributes·identity 判定；filter 取值词表缺失登记 R-ROOT-2，相关题在词表补齐前如实判 FAIL）",
    terminal: "终态与期望一致",
    dsl: "期望给出 mode/metricId/dimensions/timeExpressionRaw 时，比对审计留痕 DSL（不注入期望值）",
    llmUsage: "llm_call_trace 是否存在该 requestId 的记录（区分 LLM 路径 / 确定性降级）",
  },
  summary: {
    ...summarize(rows),
    llmUsed: rows.filter((item) => item.llmUsed).length,
    fallbackWithoutLlm: rows.filter((item) => !item.llmUsed).length,
    degradedFlagged: rows.filter((item) => item.degraded).length,
    errors: rows.filter((item) => item.code !== 0).length,
    tokensTotal: rows.reduce((sum, item) => sum + item.tokens, 0),
    tokensPerCaseAvg: rows.length
      ? Math.round(rows.reduce((sum, item) => sum + item.tokens, 0) / rows.length) : 0,
    tokensPerCaseMax: rows.length ? Math.max(...rows.map((item) => item.tokens)) : 0,
    latencyP50: percentile(latencies, 50),
    latencyP95: percentile(latencies, 95),
  },
  layers: {
    analysis_mode: byLayer("analysis_mode"),
    query_shape: byLayer("query_shape"),
    scenario_entry_mode: byLayer("scenario_entry_mode"),
    risk_type: byLayer("risk_type"),
  },
  failureSamples: failures.slice(0, 60).map((item) => ({
    caseId: item.caseId,
    runIndex: item.runIndex,
    terminal: item.terminal,
    failReasons: item.failReasons,
    errorMessage: item.errorMessage,
  })),
  rows,
};
writeFileSync(OUT, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");

console.log(`\nLLM 路径评测：${evidence.summary.passed}/${evidence.summary.total} pass`
  + `（rate=${evidence.summary.rate}，Wilson95=${JSON.stringify(evidence.summary.wilson95)}）`);
console.log(`LLM 记录 ${evidence.summary.llmUsed} 题 / 降级 ${evidence.summary.fallbackWithoutLlm} 题`
  + ` / 错误 ${evidence.summary.errors}`);
console.log(`token 合计 ${evidence.summary.tokensTotal}，均值 ${evidence.summary.tokensPerCaseAvg}`
  + `，最大 ${evidence.summary.tokensPerCaseMax}`);
console.log(`延迟 P50=${evidence.summary.latencyP50}ms P95=${evidence.summary.latencyP95}ms`);
console.log(`→ ${OUT}`);
