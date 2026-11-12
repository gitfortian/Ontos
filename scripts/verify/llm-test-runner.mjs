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
 *   # 先看执行计划，不调用任何 API：
 *   node scripts/verify/llm-test-runner.mjs --base-url http://127.0.0.1:8080 \
 *     --set docs/06-verification/evaluation/E7复杂度子集-v1.json --runs 1 \
 *     --out target/llm-complexity.json
 *
 *   # 确认计划后执行；默认并发 1、请求间隔 2000ms：
 *   node scripts/verify/llm-test-runner.mjs ... --confirm
 *   # 中断后继续：
 *   node scripts/verify/llm-test-runner.mjs ... --confirm --resume
 *
 * 外部 API 安全模式：默认并发 1；429/5xx/网络/客户端超时连续失败会熔断；
 * 连续 degraded 也会熔断（模型故障被编排层转成确定性降级时不继续烧 API）。
 * 需要恢复旧并发行为必须显式传 --allow-unsafe-concurrency，仅限本地协议桩。
 */
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { appendFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

/**
 * 读取仓库根 `.env.local`：只用于证据元数据（模型名 / 端点），不参与调用。
 * O-1（收口）：此前 runner 进程不加载该文件，证据里恒为 `model=null`，模型名只能靠服务端配置反推。
 * 进程环境优先（--env 或 shell 导出），其次 `.env.local`，两者都没有才记 null 并告警。
 */
function readDotenv(path) {
  const vars = {};
  if (!existsSync(path)) {
    return vars;
  }
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) {
      continue;
    }
    const at = trimmed.indexOf("=");
    if (at <= 0) {
      continue;
    }
    const key = trimmed.slice(0, at).trim();
    let value = trimmed.slice(at + 1).trim();
    if ((value.startsWith("\"") && value.endsWith("\""))
      || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    vars[key] = value;
  }
  return vars;
}

const ENV_FILE_VARS = readDotenv(`${repo}/.env.local`);
const RESOLVED_MODEL = process.env.ONTOS_AGENT_MODEL ?? ENV_FILE_VARS.ONTOS_AGENT_MODEL ?? null;
const RESOLVED_MODEL_ENDPOINT = process.env.ONTOS_AGENT_BASE_URL ?? ENV_FILE_VARS.ONTOS_AGENT_BASE_URL ?? null;
const MODEL_SOURCE = process.env.ONTOS_AGENT_MODEL
  ? "process-env"
  : (ENV_FILE_VARS.ONTOS_AGENT_MODEL ? ".env.local" : "unknown");
if (!RESOLVED_MODEL) {
  console.warn("  [warn] 未解析到模型名（进程环境与 .env.local 均无 ONTOS_AGENT_MODEL），证据 model 将为 null");
}
const argOf = (name, fallback) => {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : fallback;
};
const hasFlag = (name) => process.argv.includes(name);
const BASE = argOf("--base-url", "http://127.0.0.1:8080");
const SET_PATH = resolve(repo, argOf("--set", "docs/06-verification/evaluation/test集-v5.json"));
const OUT = resolve(repo, argOf("--out", "docs/06-verification/evaluation/LLM路径评测证据-v1.json"));
const CHECKPOINT = `${OUT}.checkpoint.jsonl`;
const RUNS = Number(argOf("--runs", "3"));
const RAW_CONCURRENCY = Number(argOf("--concurrency", "1"));
const ALLOW_UNSAFE_CONCURRENCY = hasFlag("--allow-unsafe-concurrency");
const CONCURRENCY = Math.max(1, RAW_CONCURRENCY);
const MIN_INTERVAL_MS = Number(argOf("--min-interval-ms", "2000"));
const MAX_CONSECUTIVE_ERRORS = Number(argOf("--max-consecutive-errors", "3"));
const MAX_CONSECUTIVE_DEGRADED = Number(argOf("--max-consecutive-degraded", "8"));
const CONFIRM = hasFlag("--confirm");
const RESUME = hasFlag("--resume");
const RESET_CHECKPOINT = hasFlag("--reset-checkpoint");
const RESUME_ATTEMPT = RESUME ? argOf("--resume-attempt", Date.now().toString(36)) : "";
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
const requestIdOf = (runIndex, caseId, turnIndex) => `llml-${RUN_ID}${RESUME_ATTEMPT ? `-x${RESUME_ATTEMPT}` : ""}-r${runIndex}-${caseId}-t${turnIndex}`;

if (!Number.isFinite(RUNS) || RUNS < 1 || !Number.isInteger(RUNS)) {
  throw new Error("--runs 必须是正整数");
}
if (!Number.isFinite(MIN_INTERVAL_MS) || MIN_INTERVAL_MS < 0) {
  throw new Error("--min-interval-ms 不能为负数");
}
if (CONCURRENCY > 1 && !ALLOW_UNSAFE_CONCURRENCY) {
  throw new Error("外部模型路径默认禁止并发 > 1；如确要并发，请显式加 --allow-unsafe-concurrency（仅限本地协议桩）");
}
if (hasFlag("--self-test")) {
  selfTest();
}
if (!CONFIRM) {
  const plannedCases = CASES.length;
  const plannedTasks = plannedCases * RUNS;
  const plannedHttpCalls = CASES.reduce((sum, item) => sum
    + (Array.isArray(item.turns) && item.turns.length ? item.turns.length : 1), 0) * RUNS;
  console.log("执行计划（未确认，未调用 API）：");
  console.log(`  题集=${SET_PATH}  有效题数=${plannedCases}`);
  console.log(`  runs=${RUNS}  任务数=${plannedTasks}  HTTP 请求数=${plannedHttpCalls}`);
  console.log(`  并发=${CONCURRENCY}  最小请求间隔=${MIN_INTERVAL_MS}ms`);
  console.log(`  熔断：连续失败=${MAX_CONSECUTIVE_ERRORS}，连续 degraded=${MAX_CONSECUTIVE_DEGRADED}`);
  console.log(`  证据=${OUT}`);
  console.log(`  checkpoint=${CHECKPOINT}`);
  console.log("确认执行请追加 --confirm；中断后续跑追加 --confirm --resume。");
  process.exit(0);
}


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

const TASKS = [];
for (let runIndex = 1; runIndex <= RUNS; runIndex += 1) {
  for (let caseIndex = 0; caseIndex < CASES.length; caseIndex += 1) {
    TASKS.push({ index: TASKS.length, runIndex, caseItem: CASES[caseIndex] });
  }
}

const SET_RELATIVE_PATH = SET_PATH.replace(`${repo}/`, "");
const CONFIG_FINGERPRINT = createHash("sha256").update(JSON.stringify({
  version: 1,
  base: BASE,
  setRelativePath: SET_RELATIVE_PATH,
  caseIds: CASES.map((item) => item.id),
  runs: RUNS,
}), "utf8").digest("hex");

const checkpointHeader = () => ({
  type: "header",
  version: 1,
  runId: RUN_ID,
  resumeAttempt: RESUME_ATTEMPT || null,
  setPath: SET_RELATIVE_PATH,
  setVersion: SET.version ?? null,
  configFingerprint: CONFIG_FINGERPRINT,
  plannedTasks: TASKS.length,
  concurrency: CONCURRENCY,
  minIntervalMs: MIN_INTERVAL_MS,
  startedAt: new Date().toISOString(),
});

function readCheckpoint() {
  if (!existsSync(CHECKPOINT)) {
    return { header: null, results: new Map() };
  }
  const lines = readFileSync(CHECKPOINT, "utf8").split(/\r?\n/).filter(Boolean);
  const parsed = lines.map((line, lineNo) => {
    try {
      return JSON.parse(line);
    } catch (error) {
      throw new Error(`checkpoint 第 ${lineNo + 1} 行不是合法 JSON：${error.message}`);
    }
  });
  const header = parsed.find((item) => item.type === "header");
  if (!header) throw new Error("checkpoint 缺少 header");
  if (header.configFingerprint !== CONFIG_FINGERPRINT) {
    throw new Error("checkpoint 与当前题集/runs/服务配置不一致；请换一个 --out 或先 --reset-checkpoint");
  }
  const results = new Map();
  for (const item of parsed) {
    if (item.type === "result" && Number.isInteger(item.index) && item.result) {
      results.set(item.index, item.result);
    }
  }
  return { header, results };
}

function initializeCheckpoint() {
  mkdirSync(dirname(OUT), { recursive: true });
  if (RESET_CHECKPOINT && existsSync(CHECKPOINT)) rmSync(CHECKPOINT);
  if (RESUME) {
    if (!existsSync(CHECKPOINT)) throw new Error("--resume 指定的 checkpoint 不存在");
    return readCheckpoint();
  }
  if (existsSync(CHECKPOINT)) {
    throw new Error("checkpoint 已存在；续跑加 --resume，重新开始加 --reset-checkpoint");
  }
  appendFileSync(CHECKPOINT, `${JSON.stringify(checkpointHeader())}\n`, "utf8");
  return { header: checkpointHeader(), results: new Map() };
}

const checkpointState = initializeCheckpoint();
const completedResults = checkpointState.results;
let nextRequestAllowedAt = 0;
const circuit = { open: false, reason: null, consecutiveErrors: 0, consecutiveDegraded: 0 };

async function acquireRequestSlot() {
  const waitMs = nextRequestAllowedAt - Date.now();
  if (waitMs > 0) await new Promise((done) => setTimeout(done, waitMs));
  nextRequestAllowedAt = Date.now() + MIN_INTERVAL_MS;
}

function appendCheckpoint(item) {
  appendFileSync(CHECKPOINT, `${JSON.stringify(item)}\n`, "utf8");
}

function noteExecutionOutcome(result) {
  if (result.failureKind) {
    circuit.consecutiveErrors += 1;
    circuit.consecutiveDegraded = 0;
    if (circuit.consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
      circuit.open = true;
      circuit.reason = `连续 ${circuit.consecutiveErrors} 次 ${result.failureKind}`;
    }
    return;
  }
  if (result.code === 0 && result.degraded === true) {
    circuit.consecutiveDegraded += 1;
    circuit.consecutiveErrors = 0;
    if (circuit.consecutiveDegraded >= MAX_CONSECUTIVE_DEGRADED) {
      circuit.open = true;
      circuit.reason = `连续 ${circuit.consecutiveDegraded} 次编排层降级（疑似模型故障）`;
    }
    return;
  }
  circuit.consecutiveErrors = 0;
  circuit.consecutiveDegraded = 0;
}

class ExternalApiError extends Error {
  constructor(message, failureKind, httpStatus = null) {
    super(message);
    this.name = "ExternalApiError";
    this.failureKind = failureKind;
    this.httpStatus = httpStatus;
  }
}

async function askOnce(caseItem, runIndex) {
  const sessionId = `s-${requestIdOf(runIndex, caseItem.id, 1)}`;
  const turns = turnsOf(caseItem);
  let last = null;
  const turnTerminals = [];
  for (let index = 0; index < turns.length; index += 1) {
    const requestId = requestIdOf(runIndex, caseItem.id, index + 1);
    const started = Date.now();
    try {
      await acquireRequestSlot();
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
      if (!response.ok) {
        const bodyText = await response.text().catch(() => "");
        throw new ExternalApiError(
          `HTTP ${response.status}: ${bodyText.slice(0, 120)}`,
          response.status === 429 ? "http_429" : response.status >= 500 ? "http_5xx" : `http_${response.status}`,
          response.status,
        );
      }
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
      if (last.code === 0 && last.terminal === "waiting") {
        last.failureKind = "waiting_timeout";
        last.httpStatus = null;
      }
    } catch (error) {
      const failureKind = error instanceof ExternalApiError
        ? error.failureKind
        : error?.name === "AbortError" || error?.name === "TimeoutError" ? "client_timeout" : "transport_error";
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
        failureKind,
        httpStatus: error instanceof ExternalApiError ? error.httpStatus : null,
      };
      turnTerminals.push(null);
    }
    if (last?.failureKind || circuit.open) break;
  }
  return { ...last, turnCount: turns.length, turnTerminals };
}

function judge(caseItem, result, dsl) {
  const expected = caseItem.expected ?? {};
  // v2.3：确定性降级路径不在 LLM 判据覆盖范围——当轮没走到模型，期望终态（如 query）
  // 与降级路径的实际终态（如澄清）本来就不同口径。整题记 skipped（不计入分母），
  // 不用错误判据判负，也不假装通过。降级是否发生由服务端响应 degraded 决定（O-2：澄清终态也要透传）。
  if (result.degraded === true) {
    return {
      pass: false,
      skipped: true,
      reasons: ["degraded:确定性降级路径，LLM 判据不适用"
        + (caseItem.degradedPathNote ? "（题集已声明降级路径判据）" : "")],
    };
  }
  // 题集 v5 允许复合期望（例如「query 或 clarification」「out_of_capability 或 clarification」）。
  // runner 判据 v2.1：按「或」拆开；服务端护栏业务拒绝在期望包含 out_of_capability 时计入合法拒绝。
  const expectedTerminals = String(expected.terminal ?? "")
    .split(/或/)
    .map((item) => item.trim())
    .filter(Boolean);
  // 预期拒绝（out_of_capability）在 ask 路径有两种合法形态：
  //   a) terminal=out_of_capability（code=0）；b) 服务端护栏/校验直接拒绝（业务错误码 ≠0，如粒度扩散 1002000014）。
  // 只认业务错误码（Ontos 码为 10 位 1_0xx_xxx_xxx）；code=null（网络/超时）与 HTTP 5xx 不算预期拒绝。
  // 命中 b) 时跳过全部 DSL 判定（被拒绝的请求没有可对账的 DSL）。
  const businessRejected = expectedTerminals.includes("out_of_capability")
    && typeof result.code === "number" && result.code >= 1_000_000_000;
  if (businessRejected) {
    return {
      pass: true,
      reasons: [],
      rejectedByGuardrail: result.code,
      rejectionMessage: result.errorMessage,
    };
  }
  const terminalOk = expectedTerminals.length === 0 || expectedTerminals.includes(result.terminal);
  // DSL 只在实际执行 query/composite 时对账；模型合法澄清/拒答时没有可对账 DSL。
  const needsDsl = ["query", "composite"].includes(result.terminal ?? "");
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
  // v2.2：LOOKUP_BATCH 的身份在 dsl.identities 中按请求顺序对齐；
  // expected.identity.value 只要求出现在批量列表中（存在性保护由执行器逐行 available=false 承担）。
  const identities = (dsl?.identities ?? []).map(String);
  const identityOk = !expected.identity
    || String(dsl?.identity?.value ?? "") === String(expected.identity.value ?? "")
    || identities.includes(String(expected.identity.value ?? ""));
  const reasons = [];
  if (!terminalOk) reasons.push(`terminal:${result.terminal}≠${expected.terminal}`);
  if (!modeOk) reasons.push(`mode:${dsl?.mode}≠${expected.mode}`);
  if (!metricOk) reasons.push(`metric:${dsl?.metricId}≠${expected.metricId}`);
  if (!dimsOk) reasons.push(`dims:${dimensions.join(",")}≠${(expected.dimensions ?? []).join(",")}`);
  if (!timeOk) reasons.push(`time:${dsl?.timeExpressionRaw}≠${expected.timeExpressionRaw}`);
  if (!filtersOk) reasons.push(`filters:${JSON.stringify(dslFilters)}≠${JSON.stringify(expected.filters ?? [])}`);
  if (!attributesOk) reasons.push(`attributes:${attributes.join(",")}≠${(expected.attributes ?? []).join(",")}`);
  if (!identityOk) reasons.push(`identity:${dsl?.identity?.value ?? identities.join(",")}≠${expected.identity?.value}`);
  return { pass: reasons.length === 0, reasons };
}

/**
 * 判据自检（--self-test）：不发任何请求，只验证判据与元数据解析。
 * 覆盖 O-1（模型名从 .env.local 解析）与 O-2（确定性降级路径整题 skipped）。
 */
function selfTest() {
  const checks = [];
  const assert = (name, ok, detail = "") => checks.push({ name, ok, detail });

  assert("O-1 模型名已解析", Boolean(RESOLVED_MODEL),
    `model=${RESOLVED_MODEL ?? "null"} source=${MODEL_SOURCE}`);

  const degradedCase = {
    expected: { terminal: "query" },
    degradedPathNote: "确定性降级路径判据：T1 query + T2 澄清",
  };
  const degraded = judge(degradedCase,
    { degraded: true, terminal: "clarification", code: 0 }, null);
  assert("O-2 降级题记 skipped", degraded.skipped === true, JSON.stringify(degraded));
  assert("O-2 降级题不计通过", degraded.pass === false, JSON.stringify(degraded));

  const degradedNoNote = judge({ expected: { terminal: "query" } },
    { degraded: true, terminal: "clarification", code: 0 }, null);
  assert("O-2 无 degradedPathNote 的降级题同样 skipped",
    degradedNoNote.skipped === true, JSON.stringify(degradedNoNote));

  const okCase = judge({ expected: { terminal: "query", mode: "QUERY" } },
    { degraded: false, terminal: "query", code: 0 }, { mode: "QUERY" });
  assert("判据正向用例", okCase.pass === true && !okCase.skipped, JSON.stringify(okCase));

  for (const check of checks) {
    console.log(`  [${check.ok ? "PASS" : "FAIL"}] ${check.name}${check.detail ? ` — ${check.detail}` : ""}`);
  }
  const failed = checks.filter((check) => !check.ok).length;
  console.log(`判据自检：${checks.length - failed}/${checks.length} PASS`);
  process.exit(failed === 0 ? 0 : 1);
}

if (hasFlag("--self-test")) {
  selfTest();
}

const results = [];
let cursor = 0;
let completedTasks = completedResults.size;
async function worker() {
  while (cursor < TASKS.length && !circuit.open) {
    const task = TASKS[cursor];
    cursor += 1;
    if (completedResults.has(task.index)) continue;
    const result = await askOnce(task.caseItem, task.runIndex);
    result.taskIndex = task.index;
    completedResults.set(task.index, result);
    results.push(result);
    completedTasks = completedResults.size;
    appendCheckpoint({ type: "result", index: task.index, result });
    noteExecutionOutcome(result);
    if (circuit.open) appendCheckpoint({ type: "circuit-open", reason: circuit.reason, at: new Date().toISOString() });
    if (completedTasks % 5 === 0 || completedTasks === TASKS.length) {
      console.log(`  ... ${completedTasks}/${TASKS.length} 已完成${circuit.open ? "（已熔断）" : ""}`);
    }
  }
}
await Promise.all(Array.from({ length: CONCURRENCY }, worker));
const skippedByResume = Math.max(0, completedResults.size - results.length);
const notRun = Math.max(0, TASKS.length - completedResults.size);
for (const [index, result] of completedResults) {
  if (!results.includes(result)) results.push(result);
}
results.sort((left, right) => (left.taskIndex ?? 0) - (right.taskIndex ?? 0));

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
    identities: (dsl?.identities ?? []).map(String),
    identityValue: dsl?.identity?.value ?? null,
    llmCalls: usage.calls,
    tokens: usage.tokens,
    llmUsed: usage.calls > 0,
    pass: judgement.pass,
    skipped: judgement.skipped === true,
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
  // skipped（如确定性降级路径）不计入分母：判据不适用时既不算通过也不算失败。
  const scored = items.filter((item) => !item.skipped);
  const total = scored.length;
  const passed = scored.filter((item) => item.pass).length;
  return {
    total,
    passed,
    rate: total ? Number((passed / total).toFixed(4)) : null,
    wilson95: wilson(passed, total),
    skipped: items.length - total,
  };
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
const failures = rows.filter((item) => !item.pass && !item.skipped);
const evidence = {
  task: "M7/W2/T2.2",
  runId: RUN_ID,
  ranAt: new Date().toISOString(),
  serviceBaseUrl: BASE,
  model: RESOLVED_MODEL,
  modelSource: MODEL_SOURCE,
  modelEndpoint: RESOLVED_MODEL_ENDPOINT,
  testSet: { path: SET_PATH.replace(`${repo}/`, ""), version: SET.version, total: SET.total, evaluated: CASES.length },
  runs: RUNS,
  execution: {
    plannedTasks: TASKS.length,
    completedTasks,
    skippedByResume,
    notRun,
    concurrency: CONCURRENCY,
    minIntervalMs: MIN_INTERVAL_MS,
    circuitBreaker: { ...circuit },
    checkpointPath: CHECKPOINT.replace(`${repo}/`, ""),
    resumed: RESUME,
  },
  judgement: {
    version: "v2.3（v2.2 基础上：确定性降级路径 + 题集带 degradedPathNote → 整题 skipped，不计入分母；LOOKUP_BATCH dsl.identities 按请求顺序对齐；服务端护栏拒绝在期望包含 out_of_capability 时计入合法拒绝；filter 取值词表缺失仍登记 R-ROOT-2）",
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
if (completedResults.size === TASKS.length) {
  appendCheckpoint({ type: "complete", completedAt: new Date().toISOString(), resultCount: completedResults.size });
}

console.log(`\nLLM 路径评测：${evidence.summary.passed}/${evidence.summary.total} pass`
  + `（rate=${evidence.summary.rate}，Wilson95=${JSON.stringify(evidence.summary.wilson95)}`
  + `，skip=${evidence.summary.skipped}）`);
console.log(`LLM 记录 ${evidence.summary.llmUsed} 题 / 降级 ${evidence.summary.fallbackWithoutLlm} 题`
  + ` / 错误 ${evidence.summary.errors}`);
console.log(`token 合计 ${evidence.summary.tokensTotal}，均值 ${evidence.summary.tokensPerCaseAvg}`
  + `，最大 ${evidence.summary.tokensPerCaseMax}`);
console.log(`延迟 P50=${evidence.summary.latencyP50}ms P95=${evidence.summary.latencyP95}ms`);
console.log(`→ ${OUT}`);
