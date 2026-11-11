#!/usr/bin/env node
/**
 * LLM 调用追踪端到端探针（M7/W5/T5.1）。
 *
 * 口径：模型侧使用本地 OpenAI 协议桩（scripts/verify/mock-openai-model.mjs），
 * **只证明「模型调用发生且被 llm_call_trace 记录」**，不代表模型效果；
 * E-7 的 LLM 路径评测必须使用真实模型服务，不得引用本脚本结论。
 *
 * 验证项：
 *   T1 同一次 ask 请求的 traceId（X-Request-Id）→ llm_call_trace.trace_id 一致
 *   T2 记录含模型名 / token 用量 / 耗时 / 终态（success）
 *   T3 每次模型调用恰好一条记录（与桩侧调用计数一致）；同一次 ask 可能有多条（提案 + 记忆整理）
 *   T4 观测查询接口：按 traceId 与「最近调用」两条入口都可用
 *
 * 用法：
 *   node scripts/verify/llm-trace-check.mjs                       # 自起桩 + 自起服务
 *   node scripts/verify/llm-trace-check.mjs --base-url http://127.0.0.1:8080
 */
import { spawn } from "node:child_process";
import { createWriteStream, existsSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const OUT = resolve(repo, "docs/06-verification/evaluation/LLM调用追踪证据-v1.json");
const MOCK_PORT = 18080;
const BASE = process.argv.includes("--base-url")
  ? process.argv[process.argv.indexOf("--base-url") + 1]
  : "http://127.0.0.1:8080";
const JAR = resolve(repo, "ontos/ontos-server/target/ontos-server-0.1.0-SNAPSHOT.jar");
const spawnBackend = !process.argv.includes("--base-url");

const results = [];
const note = (id, name, pass, detail) => {
  results.push({ id, name, pass, detail });
  console.log(`${pass ? "PASS" : "FAIL"} [${id}] ${name}${detail ? " — " + JSON.stringify(detail).slice(0, 200) : ""}`);
};

const children = [];
const wait = (ms) => new Promise((resolveWait) => setTimeout(resolveWait, ms));

async function waitFor(url, predicate, label, attempts = 90) {
  for (let index = 0; index < attempts; index += 1) {
    try {
      const response = await fetch(url);
      const body = await response.json().catch(() => null);
      if (predicate(response, body)) {
        return body;
      }
    } catch {
      // 未就绪，继续轮询
    }
    await wait(1000);
  }
  throw new Error(`等待超时：${label}`);
}

function shutdown() {
  for (const child of children) {
    try {
      child.kill("SIGTERM");
    } catch {
      // ignore
    }
  }
}

try {
  if (spawnBackend) {
    if (!existsSync(JAR)) {
      throw new Error(`缺少服务 jar，请先 mvn -DskipTests package: ${JAR}`);
    }
    const mockLog = createWriteStream(resolve(repo, "target/mock-openai.log"), { flags: "w" });
    const mock = spawn("node", [resolve(repo, "scripts/verify/mock-openai-model.mjs"),
      "--port", String(MOCK_PORT)], { stdio: ["ignore", "pipe", "pipe"] });
    mock.stdout.pipe(mockLog);
    mock.stderr.pipe(mockLog);
    children.push(mock);

    const serverLog = createWriteStream(resolve(repo, "target/llm-trace-server.log"), { flags: "w" });
    const server = spawn("java", ["-jar", JAR, "--spring.profiles.active=local",
      "--server.port=8080"], {
      env: {
        ...process.env,
        ONTOS_AGENT_BASE_URL: `http://127.0.0.1:${MOCK_PORT}/v1`,
        ONTOS_AGENT_API_KEY: "stub-key-for-trace-check",
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
  if (spawnBackend) {
    await waitFor(`http://127.0.0.1:${MOCK_PORT}/__stats`, (response) => response.ok, "模型桩就绪");
  }

  const runId = Date.now();
  const traceId = `llmtrace-${runId}`;
  const askResponse = await fetch(`${BASE}/api/v1/ask/runs`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Ontos-Tenant": "demo",
      "X-Ontos-User": "llm-trace-check",
      "X-Ontos-Session": `s-llmtrace-${runId}`,
      "X-Ontos-Turn": "1",
      "X-Request-Id": traceId,
    },
    body: JSON.stringify({ utterance: "今年各地区的成交金额是多少" }),
  });
  const echoed = askResponse.headers.get("X-Request-Id");
  const askBody = await askResponse.json();
  note("T1a", "响应回显同一 traceId", echoed === traceId, { echoed, traceId });
  note("T1b", "ask 请求成功受理", askBody?.code === 0,
    { code: askBody?.code, terminal: askBody?.data?.terminal, msg: askBody?.msg });

  // 模型调用记录是流终态后写入，给一个短等待窗口（不轮询超时即失败）
  let rows = [];
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const listResponse = await fetch(
      `${BASE}/api/v1/evaluation/llm-calls?traceId=${encodeURIComponent(traceId)}&limit=20`,
      { headers: { "X-Ontos-Tenant": "demo", "X-Ontos-User": "llm-trace-check" } },
    );
    const listBody = await listResponse.json();
    rows = listBody?.data ?? [];
    if (rows.length > 0) {
      break;
    }
    await wait(500);
  }

  note("T2a", "同一 traceId 命中模型调用记录", rows.length > 0, { count: rows.length });
  const row = rows[0] ?? {};
  note("T2b", "记录字段完整（模型/用量/耗时/终态）",
    row.traceId === traceId && Boolean(row.model) && row.status === "success"
      && Number(row.totalTokens) > 0 && Number(row.latencyMs) >= 0,
    { traceId: row.traceId, model: row.model, tokens: row.totalTokens, latencyMs: row.latencyMs, status: row.status });
  note("T2c", "调用目的标记为 ask.agent", row.purpose === "ask.agent", { purpose: row.purpose });
  note("T2d", "会话与轮次随记录落库",
    row.sessionId === `s-llmtrace-${runId}` && row.turnIndex === 1,
    { sessionId: row.sessionId, turnIndex: row.turnIndex });

  if (spawnBackend) {
    const stats = await fetch(`http://127.0.0.1:${MOCK_PORT}/__stats`).then((response) => response.json());
    note("T3", "每次模型调用恰好一条记录", stats.calls === rows.length,
      { mockCalls: stats.calls, traceRows: rows.length });
  } else {
    note("T3", "每次模型调用恰好一条记录（外部服务未提供桩计数，跳过）", true, { skipped: true });
  }

  const recentResponse = await fetch(`${BASE}/api/v1/evaluation/llm-calls?limit=5`,
    { headers: { "X-Ontos-Tenant": "demo", "X-Ontos-User": "llm-trace-check" } });
  const recentBody = await recentResponse.json();
  note("T4", "最近调用入口可用", recentBody?.code === 0 && Array.isArray(recentBody.data)
    && recentBody.data.length > 0, { count: recentBody?.data?.length });

  const summary = {
    total: results.length,
    passed: results.filter((item) => item.pass).length,
    failed: results.filter((item) => !item.pass).length,
  };
  writeFileSync(OUT, JSON.stringify({
    task: "M7/W5/T5.1",
    generatedAt: new Date().toISOString(),
    serviceBaseUrl: BASE,
    modelSide: "local OpenAI-compatible stub（仅验证追踪链路，不代表模型效果）",
    traceId,
    summary,
    results,
    observations: [
      `本次 ask 共落 ${rows.length} 条模型调用记录：意图提案 + Harness 的 MemoryConsolidator 后台整理（合规提案下两次运行均为 1+2）。`,
      "成本与 E-7 统计必须按调用累加，不能假设「一次问数一次模型调用」；单条记录 token 取模型返回的 usage 汇总。",
      "提案不合规时会触发修复轮，调用数成倍放大（实测缺 reason_code 时单次 ask 达 13 条），修复轮的调用同样被记录。",
    ],
    sample: row,
  }, null, 2) + "\n");
  console.log(`\n${summary.passed}/${summary.total} PASS → ${OUT}`);
  if (summary.failed > 0) {
    process.exitCode = 1;
  }
} finally {
  shutdown();
}
