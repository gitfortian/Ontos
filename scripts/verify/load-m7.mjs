#!/usr/bin/env node
/**
 * M7 压测（W5/T5.2）：三档并发 + LLM 链路抽样。
 *
 * SLO（看结果前已冻结，见 docs/06-verification/evaluation/SLO与成本预算-v1.md）：
 *   确定性链路：P95 ≤ 2000 ms、错误率 = 0、超时率 = 0（并发 1 / 8 / 16）
 *   LLM 链路：单题 token ≤ 8000、P95 ≤ 6000 ms（本脚本的模型侧是本地协议桩，
 *             **只测编排 + 追踪开销，不代表模型延迟与成本**；E-7 必须用真实模型）
 * 基线对照：M6 证据 `压测-M6证据-v2.json` 的并发 16 P95=145ms 作为回归下限；
 *           劣化 > 30% 需在报告里解释（SLO 文档 §4 第 3 条），不直接判失败。
 *
 * 用法：
 *   node scripts/verify/load-m7.mjs                     # 自起桩 + 自起服务（LLM 指向桩）
 *   node scripts/verify/load-m7.mjs --base-url http://127.0.0.1:8080
 *   node scripts/verify/load-m7.mjs --skip-llm          # 只跑确定性三档
 */
import { spawn } from "node:child_process";
import { createWriteStream, existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const has = (flag) => process.argv.includes(flag);
const OUT = has("--out")
  ? resolve(repo, process.argv[process.argv.indexOf("--out") + 1])
  : resolve(repo, "docs/06-verification/evaluation/压测-M7证据-v1.json");
const M6_EVIDENCE = resolve(repo, "docs/06-verification/evaluation/压测-M6证据-v2.json");
const JAR = resolve(repo, "ontos/ontos-server/target/ontos-server-0.1.0-SNAPSHOT.jar");
const MOCK_PORT = 18080;
const BASE = has("--base-url")
  ? process.argv[process.argv.indexOf("--base-url") + 1]
  : "http://127.0.0.1:8080";
const SPAWN = !has("--base-url");
const SKIP_LLM = has("--skip-llm");
const SLO = { p95Ms: 2000, errorRate: 0, timeoutRate: 0, llmP95Ms: 6000, llmTokensPerCall: 8000 };

const wait = (ms) => new Promise((done) => setTimeout(done, ms));
const children = [];
const shutdown = () => {
  for (const child of children) {
    try {
      child.kill("SIGTERM");
    } catch {
      // ignore
    }
  }
};

async function waitFor(url, predicate, label, attempts = 90) {
  for (let index = 0; index < attempts; index += 1) {
    try {
      const response = await fetch(url);
      const body = await response.json().catch(() => null);
      if (predicate(response, body)) {
        return body;
      }
    } catch {
      // 未就绪
    }
    await wait(1000);
  }
  throw new Error(`等待超时：${label}`);
}

try {
  if (SPAWN) {
    if (!existsSync(JAR)) {
      throw new Error(`缺少服务 jar，请先 mvn -DskipTests package: ${JAR}`);
    }
    const mockLog = createWriteStream(resolve(repo, "target/load-mock.log"), { flags: "w" });
    const mock = spawn("node", [resolve(repo, "scripts/verify/mock-openai-model.mjs"),
      "--port", String(MOCK_PORT)], { stdio: ["ignore", "pipe", "pipe"] });
    mock.stdout.pipe(mockLog);
    mock.stderr.pipe(mockLog);
    children.push(mock);

    const serverLog = createWriteStream(resolve(repo, "target/load-server.log"), { flags: "w" });
    const server = spawn("java", ["-jar", JAR, "--spring.profiles.active=local",
      "--server.port=8080"], {
      env: {
        ...process.env,
        ONTOS_AGENT_BASE_URL: `http://127.0.0.1:${MOCK_PORT}/v1`,
        ONTOS_AGENT_API_KEY: "stub-key-for-load",
        ONTOS_AGENT_MODEL: "stub-model",
      },
      stdio: ["ignore", "pipe", "pipe"],
    });
    server.stdout.pipe(serverLog);
    server.stderr.pipe(serverLog);
    children.push(server);
  }

  await waitFor(`${BASE}/actuator/health`, (response, body) => response.ok && body?.status === "UP",
    "服务健康检查");

  const manifestId = await fetch(`${BASE}/api/v1/ontology/manifests/current`, {
    headers: { "X-Ontos-Tenant": "demo", "X-Ontos-User": "load" },
  }).then((response) => response.json()).then((body) => body?.data?.manifestId);
  const runId = `L7${Date.now()}`;
  let seq = 0;

  /** 混合负载（与真实分布近似）：聚合 60% / lookup 20% / 复合 20%。 */
  function pickCase() {
    const roll = Math.random();
    if (roll < 0.6) {
      const dims = roll < 0.3 ? ["dim.region"] : roll < 0.45 ? ["dim.channel"] : [];
      return { path: "/api/v1/query/dry-run", body: {
        manifestId, mode: "QUERY", metricId: "metric.gmv", dimensions: dims,
        timeExpressionRaw: "上个月", timeRange: { field: "dim.order_date" },
      } };
    }
    if (roll < 0.8) {
      return { path: "/api/v1/query/dry-run", body: {
        manifestId, mode: "LOOKUP", entityId: "concept.customer",
        attributes: ["property.customer.tier"],
        identity: { propertyId: "property.customer.customer_id", value: "c-1001" }, limit: 1,
      } };
    }
    return { path: "/api/v1/query/composite/execute", body: {
      manifestId, mode: "QUERY",
      subIntents: [
        { subIntentId: "s1", metricId: "metric.gmv", mode: "QUERY", dimensions: ["dim.region"], timeExpressionRaw: "上个月", timeRange: { field: "dim.order_date" } },
        { subIntentId: "s2", metricId: "metric.order_count", mode: "QUERY", dimensions: ["dim.channel"], timeExpressionRaw: "今年", timeRange: { field: "dim.order_date" } },
      ],
    } };
  }

  async function queryOnce() {
    seq += 1;
    const selected = pickCase();
    const started = Date.now();
    try {
      const response = await fetch(BASE + selected.path, {
        method: "POST",
        headers: {
          "Content-Type": "application/json", "X-Ontos-Tenant": "demo",
          "X-Ontos-User": `load-${runId}-${seq}`, "X-Ontos-Session": `s-${seq}`,
          "X-Ontos-Turn": "1", "X-Request-Id": `load-${runId}-${seq}`,
        },
        body: JSON.stringify(selected.body),
        signal: AbortSignal.timeout(10000),
      });
      const payload = await response.json();
      return {
        latency: Date.now() - started,
        ok: response.status < 500 && payload.code === 0,
        timeout: false,
      };
    } catch (error) {
      return {
        latency: Date.now() - started,
        ok: false,
        timeout: error.name === "TimeoutError" || error.name === "AbortError",
      };
    }
  }

  const percentile = (sorted, p) => {
    if (sorted.length === 0) {
      return null;
    }
    const index = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
    return sorted[Math.max(0, index)];
  };

  const ALL_LATENCIES = [];
  async function phase(name, concurrency, total, runner) {
    const latencies = [];
    let errors = 0;
    let timeouts = 0;
    const startedAt = Date.now();
    let launched = 0;
    const workers = Array.from({ length: concurrency }, async () => {
      while (launched < total) {
        launched += 1;
        const result = await runner();
        latencies.push(result.latency);
        ALL_LATENCIES.push(result.latency);
        if (!result.ok) {
          errors += 1;
        }
        if (result.timeout) {
          timeouts += 1;
        }
      }
    });
    await Promise.all(workers);
    const wallMs = Date.now() - startedAt;
    const sorted = [...latencies].sort((left, right) => left - right);
    return {
      name, concurrency, total,
      p50: percentile(sorted, 50), p95: percentile(sorted, 95),
      p99: percentile(sorted, 99), max: sorted.at(-1) ?? null,
      errorCount: errors, timeoutCount: timeouts,
      throughputRps: Number((total / (wallMs / 1000)).toFixed(2)),
    };
  }

  // 预热：连接池 / JIT / 查询缓存
  for (let index = 0; index < 10; index += 1) {
    await queryOnce();
  }
  ALL_LATENCIES.length = 0;

  // 单次压测抖动明显（实测同配置 118ms 与 202ms 差 70%），故重复多轮取中位数：
  // 只报一次结果等于把机器抖动写进证据，无法支撑「劣化 ≤30%」这类判断。
  const TRIALS = Number(process.argv.includes("--trials")
    ? process.argv[process.argv.indexOf("--trials") + 1] : 3);
  const trialPhases = [];
  for (let trial = 0; trial < TRIALS; trial += 1) {
    const row = [];
    row.push(await phase("确定性 · 并发 1", 1, 20, queryOnce));
    row.push(await phase("确定性 · 并发 8", 8, 80, queryOnce));
    row.push(await phase("确定性 · 并发 16", 16, 160, queryOnce));
    trialPhases.push(row);
    console.log(`trial ${trial + 1}/${TRIALS}: `
      + row.map((item) => `c${item.concurrency} P95=${item.p95}ms`).join(" · "));
  }

  const median = (values) => {
    const sortedValues = [...values].sort((left, right) => left - right);
    const middle = Math.floor(sortedValues.length / 2);
    return sortedValues.length % 2 === 0
      ? Math.round((sortedValues[middle - 1] + sortedValues[middle]) / 2)
      : sortedValues[middle];
  };

  // 聚合口径：每个并发档的 P50/P95/P99/max 取跨轮中位数，计数取求和，吞吐取中位数
  const phases = [0, 1, 2].map((index) => {
    const rows = trialPhases.map((trial) => trial[index]);
    const first = rows[0];
    return {
      name: first.name,
      concurrency: first.concurrency,
      total: rows.reduce((sum, item) => sum + item.total, 0),
      p50: median(rows.map((item) => item.p50)),
      p95: median(rows.map((item) => item.p95)),
      p99: median(rows.map((item) => item.p99)),
      max: median(rows.map((item) => item.max)),
      errorCount: rows.reduce((sum, item) => sum + item.errorCount, 0),
      timeoutCount: rows.reduce((sum, item) => sum + item.timeoutCount, 0),
      throughputRps: median(rows.map((item) => item.throughputRps)),
      perTrialP95: rows.map((item) => item.p95),
    };
  });

  const deterministic = (() => {
    const errors = phases.reduce((sum, item) => sum + item.errorCount, 0);
    const timeouts = phases.reduce((sum, item) => sum + item.timeoutCount, 0);
    const requests = phases.reduce((sum, item) => sum + item.total, 0);
    const overallP95 = percentile([...ALL_LATENCIES].sort((left, right) => left - right), 95);
    return {
      trials: TRIALS,
      phases,
      verdict: {
        p95WithinBudget: phases.every((item) => item.p95 < SLO.p95Ms),
        errorRateZero: errors === 0,
        timeoutRateZero: timeouts === 0,
        totalRequests: requests, totalErrors: errors, totalTimeouts: timeouts,
        allSamplesP95: overallP95,
      },
    };
  })();

  // ── 基线对照（M6 并发 16 P95）──
  let baseline = null;
  if (existsSync(M6_EVIDENCE)) {
    const m6 = JSON.parse(readFileSync(M6_EVIDENCE, "utf8"));
    const m6Phase = (m6.phases ?? []).find((item) => item.concurrency === 16);
    const current = phases.find((item) => item.concurrency === 16);
    if (m6Phase?.p95 && current?.p95) {
      const ratio = Number((current.p95 / m6Phase.p95).toFixed(3));
      baseline = {
        source: "压测-M6证据-v2.json（并发 16）",
        m6P95: m6Phase.p95,
        currentP95: current.p95,
        ratio,
        withinThirtyPercent: ratio <= 1.3,
      };
    }
  }

  // ── LLM 链路抽样（模型侧为本地桩：只测编排 + 追踪开销与落库完整性）──
  let llm = null;
  if (!SKIP_LLM) {
    const traceIds = [];
    let llmSeq = 0;
    const askOnce = async () => {
      llmSeq += 1;
      const traceId = `loadllm-${runId}-${llmSeq}`;
      traceIds.push(traceId);
      const started = Date.now();
      try {
        const response = await fetch(`${BASE}/api/v1/ask/runs`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json", "X-Ontos-Tenant": "demo",
            "X-Ontos-User": `load-llm-${llmSeq}`, "X-Ontos-Session": `s-llm-${runId}-${llmSeq}`,
            "X-Ontos-Turn": "1", "X-Request-Id": traceId,
          },
          body: JSON.stringify({ utterance: "今年各地区的成交金额是多少" }),
          signal: AbortSignal.timeout(30000),
        });
        const payload = await response.json();
        return {
          latency: Date.now() - started,
          ok: response.status < 500 && payload.code === 0,
          timeout: false,
        };
      } catch (error) {
        return {
          latency: Date.now() - started,
          ok: false,
          timeout: error.name === "TimeoutError" || error.name === "AbortError",
        };
      }
    };

    const askPhase = await phase("LLM · 并发 4", 4, 12, askOnce);

    // 追踪完整性：每个 trace 的模型调用记录条数（并发下不丢行）
    const perTrace = [];
    for (const traceId of traceIds) {
      const rows = await fetch(
        `${BASE}/api/v1/evaluation/llm-calls?traceId=${encodeURIComponent(traceId)}&limit=50`,
        { headers: { "X-Ontos-Tenant": "demo", "X-Ontos-User": "load" } },
      ).then((response) => response.json()).then((body) => body?.data ?? []);
      perTrace.push({ traceId, calls: rows.length, tokens: rows.reduce((sum, row) => sum + (row.totalTokens ?? 0), 0) });
    }
    const missing = perTrace.filter((item) => item.calls === 0);
    const tokenOverBudget = perTrace.filter((item) => item.tokens > SLO.llmTokensPerCall);
    llm = {
      phase: askPhase,
      traces: perTrace.length,
      missingTraces: missing.length,
      maxCallsPerTrace: Math.max(0, ...perTrace.map((item) => item.calls)),
      maxTokensPerTrace: Math.max(0, ...perTrace.map((item) => item.tokens)),
      traceComplete: missing.length === 0,
      tokenBudgetRespected: tokenOverBudget.length === 0,
      modelSide: "本地 OpenAI 协议桩（只测编排 + 追踪开销；延迟/成本不代表真实模型）",
    };
  }

  const allPass = deterministic.verdict.p95WithinBudget && deterministic.verdict.errorRateZero
    && deterministic.verdict.timeoutRateZero && deterministic.verdict.allSamplesP95 < SLO.p95Ms
    && (llm == null || (llm.traceComplete && llm.tokenBudgetRespected
      && llm.phase.p95 < SLO.llmP95Ms && llm.phase.errorCount === 0));

  const evidence = {
    runId,
    ranAt: new Date().toISOString(),
    manifestId,
    scriptVersion: "load-m7-v1",
    target: "阶段本体 T5.2：确定性三档 + LLM 链路抽样（追踪完整性与开销）",
    sloFrozenBeforeRun: SLO,
    sloBasis: "SLO与成本预算-v1（2026-11-11 冻结，看正式结果前）",
    agentMode: agentModeLabel(),
    deterministic,
    baseline,
    llm,
    verdict: {
      deterministicPass: deterministic.verdict.p95WithinBudget
        && deterministic.verdict.errorRateZero && deterministic.verdict.timeoutRateZero,
      llmSamplingPass: llm == null ? "skipped" : (llm.traceComplete && llm.tokenBudgetRespected
        && llm.phase.p95 < SLO.llmP95Ms && llm.phase.errorCount === 0),
      llmNote: "模型侧为本地 OpenAI 协议桩：只验证编排与追踪写入开销，真实模型延迟/成本以 E-7 为准",
      baselineNote: baseline == null ? "无 M6 对照证据"
        : baseline.withinThirtyPercent ? "劣化 ≤30%（未触发解释项）"
          : "劣化 >30%，需在报告中解释（SLO 文档 §4 第 3 条）",
      overall: allPass ? "SLO 全满足" : "SLO 未满足（见分项）",
    },
  };
  writeFileSync(OUT, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");

  for (const item of phases) {
    console.log(`${item.name}: n=${item.total} P50=${item.p50}ms P95=${item.p95}ms `
      + `P99=${item.p99}ms max=${item.max}ms err=${item.errorCount} `
      + `timeout=${item.timeoutCount} tput=${item.throughputRps}rps`);
  }
  if (llm) {
    console.log(`LLM 抽样: n=${llm.phase.total} P50=${llm.phase.p50}ms P95=${llm.phase.p95}ms `
      + `err=${llm.phase.errorCount} traces=${llm.traces} missing=${llm.missingTraces} `
      + `maxCalls/trace=${llm.maxCallsPerTrace} maxTokens/trace=${llm.maxTokensPerTrace}`);
  }
  if (baseline) {
    console.log(`基线对照: M6 P95=${baseline.m6P95}ms → M7 ${baseline.currentP95}ms `
      + `(x${baseline.ratio}) ${evidence.verdict.baselineNote}`);
  }
  console.log(`load-m7: ${evidence.verdict.overall} → ${OUT}`);
  if (!allPass) {
    process.exitCode = 1;
  }
} finally {
  shutdown();
}

function agentModeLabel() {
  return SPAWN
    ? "agent.enabled=true → 本地 OpenAI 协议桩（确定性端点不经过模型；ask 抽样经过）"
    : "外部服务（模型配置未知，LLM 抽样结论以服务的实际模型为准）";
}
