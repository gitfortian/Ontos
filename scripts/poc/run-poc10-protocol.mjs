#!/usr/bin/env node
/**
 * PoC #10 协议探针（M0 / W3 / T3.2）。
 *
 * 只验协议层三件事，不依赖界面，也不要求业务终态（那属 T3.5 界面复验与 M1 能力）：
 *   1. 六类 AG-UI 事件可达性：RUN_* / TEXT_MESSAGE_* / TOOL_CALL_* / CUSTOM / HITL / STATE_*；
 *   2. 断线重连幂等：同一 threadId + runId 重复提交，是否重复执行；
 *   3. RUN_STARTED.input 脱敏：事件里是否明文回显用户原始输入。
 *
 * 结论口径：
 *   - 可达 = 本次会话中实际观测到该族事件；
 *   - 不可达但本阶段不具备产生条件（如 HITL 需审批流）记为 NOT_TRIGGERED，不算通过也不算失败；
 *   - 幂等与脱敏是硬判据，不满足即 FAIL。
 *
 * 用法：
 *   node scripts/poc/run-poc10-protocol.mjs [--base-url http://127.0.0.1:8080] [--sessions 20]
 */
import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";

const argOf = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};

const BASE = argOf("--base-url", "http://127.0.0.1:8080");
const AGENT = argOf("--agent-id", "ontos");
const SESSIONS = Number(argOf("--sessions", "20"));
const TIMEOUT_MS = Number(argOf("--timeout", "120000"));
const OUTPUT = resolve(argOf("--output", "docs/06-verification/poc/PoC-10-协议探针-raw.json"));

const QUESTIONS = [
  "你好，请用一句话说明你能做什么",
  "上个月各区域的成交金额是多少",
  "帮我看看华东区的订单情况",
  "最近一周的客户数变化",
  "这个季度和上个季度比怎么样",
];

const EVENT_FAMILIES = [
  { key: "RUN", label: "RUN_STARTED / RUN_FINISHED", match: (t) => t.startsWith("RUN_") },
  { key: "TEXT_MESSAGE", label: "TEXT_MESSAGE_START/CONTENT/END", match: (t) => t.startsWith("TEXT_MESSAGE_") },
  { key: "TOOL_CALL", label: "TOOL_CALL_START/ARGS/END", match: (t) => t.startsWith("TOOL_CALL_") },
  { key: "CUSTOM", label: "CUSTOM", match: (t) => t === "CUSTOM" },
  { key: "HITL", label: "中断/审批回传", match: (t) => t.includes("INTERRUPT") || t.includes("APPROVAL") || t.includes("HUMAN") },
  { key: "STATE", label: "STATE_SNAPSHOT / STATE_DELTA", match: (t) => t.startsWith("STATE_") },
];

/** 发一次会话，收集事件。 */
async function runSession({ threadId, runId, question }) {
  const body = {
    threadId,
    runId,
    messages: [{ id: `${runId}-m1`, role: "user", content: question }],
    tools: [],
    context: [],
    state: {},
  };

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  const startedAt = Date.now();
  const events = [];
  let raw = "";
  let status = 0;

  try {
    const response = await fetch(`${BASE}/agent/run/${AGENT}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    status = response.status;
    if (!response.ok || !response.body) {
      return { threadId, runId, question, status, error: await response.text(), events, durationMs: Date.now() - startedAt };
    }
    const decoder = new TextDecoder();
    let buffer = "";
    for await (const chunk of response.body) {
      buffer += decoder.decode(chunk, { stream: true });
      const parts = buffer.split("\n\n");
      buffer = parts.pop() ?? "";
      for (const part of parts) {
        for (const line of part.split("\n")) {
          if (!line.startsWith("data:")) continue;
          const payload = line.slice(5).trim();
          if (!payload) continue;
          raw += `${payload}\n`;
          try {
            events.push(JSON.parse(payload));
          } catch {
            events.push({ type: "PARSE_ERROR", raw: payload });
          }
        }
      }
    }
  } catch (error) {
    return { threadId, runId, question, status, error: String(error), events, durationMs: Date.now() - startedAt };
  } finally {
    clearTimeout(timer);
  }

  const types = events.map((e) => String(e.type ?? "UNKNOWN"));
  const text = events
    .filter((e) => e.type === "TEXT_MESSAGE_CONTENT")
    .map((e) => e.delta ?? "")
    .join("");
  return {
    threadId,
    runId,
    question,
    status,
    durationMs: Date.now() - startedAt,
    eventCount: events.length,
    types,
    families: Object.fromEntries(EVENT_FAMILIES.map((f) => [f.key, types.filter(f.match).length])),
    textLength: text.length,
    textPreview: text.slice(0, 80),
    runStartedInput: events.find((e) => e.type === "RUN_STARTED")?.input ?? null,
    customNames: [...new Set(events.filter((e) => e.type === "CUSTOM").map((e) => e.name))],
  };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  console.log(`PoC #10 协议探针 → ${BASE}/agent/run/${AGENT}`);
  const sessions = [];

  for (let i = 0; i < SESSIONS; i++) {
    const question = QUESTIONS[i % QUESTIONS.length];
    const result = await runSession({
      threadId: `t-${i + 1}`,
      runId: `r-${i + 1}`,
      question,
    });
    sessions.push(result);
    if (i === 0 || (i + 1) % 5 === 0 || i === SESSIONS - 1) {
      console.log(
        `  [${i + 1}/${SESSIONS}] status=${result.status} 事件 ${result.eventCount ?? 0} 个 / ${result.durationMs} ms` +
          (result.error ? ` 错误 ${String(result.error).slice(0, 60)}` : ""),
      );
    }
    await sleep(300);
  }

  const ok = sessions.filter((s) => s.status === 200 && s.eventCount > 0);
  const familyStats = Object.fromEntries(
    EVENT_FAMILIES.map((f) => {
      const hit = ok.filter((s) => s.families[f.key] > 0).length;
      return [f.key, { label: f.label, sessionsWithFamily: hit, total: ok.length }];
    }),
  );

  // 幂等：同一 threadId + runId 连发 3 次
  const idempotencyRuns = [];
  const fixed = { threadId: "t-idem", runId: "r-idem", question: "幂等性测试：各区域成交金额" };
  for (let i = 0; i < 3; i++) {
    const r = await runSession(fixed);
    idempotencyRuns.push({
      attempt: i + 1,
      status: r.status,
      eventCount: r.eventCount ?? 0,
      durationMs: r.durationMs,
      textLength: r.textLength ?? 0,
      textPreview: r.textPreview ?? "",
      error: r.error ? String(r.error).slice(0, 200) : null,
    });
    await sleep(300);
  }
  const idempotent =
    idempotencyRuns.every((r) => r.status === 200) &&
    idempotencyRuns.every((r) => r.textLength === idempotencyRuns[0].textLength || r.textLength === 0);

  // 脱敏：RUN_STARTED.input 是否明文包含用户输入
  const leakSessions = ok.filter((s) => {
    const input = s.runStartedInput;
    if (!input) return false;
    const serialized = JSON.stringify(input);
    return serialized.includes(s.question);
  });

  const report = {
    generatedAt: new Date().toISOString(),
    scope: "PoC #10 协议层（事件可达性 / 幂等 / RUN_STARTED.input 脱敏）；不含界面复验与业务终态",
    baseUrl: BASE,
    agentId: AGENT,
    requestedSessions: SESSIONS,
    completedSessions: ok.length,
    eventFamilies: familyStats,
    sessions: sessions.map((s) => ({
      threadId: s.threadId,
      runId: s.runId,
      status: s.status,
      eventCount: s.eventCount ?? 0,
      durationMs: s.durationMs,
      families: s.families ?? {},
      customNames: s.customNames ?? [],
      textPreview: s.textPreview ?? "",
      error: s.error ? String(s.error).slice(0, 300) : null,
    })),
    idempotency: { runs: idempotencyRuns, sameResult: idempotent },
    redaction: {
      sessionsWithInputEcho: leakSessions.length,
      totalChecked: ok.length,
      leaked: leakSessions.length > 0,
      note: "RUN_STARTED.input 回显请求体，是否泄露取决于前端是否把它写入日志/错误上报",
    },
    verdict: {
      eventsReachable: Object.values(familyStats).filter((f) => f.sessionsWithFamily > 0).length,
      eventsTotal: EVENT_FAMILIES.length,
      idempotent,
      redactionRequired: leakSessions.length > 0,
    },
  };

  mkdirSync(dirname(OUTPUT), { recursive: true });
  writeFileSync(OUTPUT, `${JSON.stringify(report, null, 2)}\n`, "utf8");

  console.log("\n  六类事件可达性");
  for (const family of EVENT_FAMILIES) {
    const stat = familyStats[family.key];
    const flag = stat.sessionsWithFamily === 0 ? "NOT_TRIGGERED" : `${stat.sessionsWithFamily}/${stat.total}`;
    console.log(`    ${family.key.padEnd(14)} ${flag.padEnd(16)} ${family.label}`);
  }
  console.log(`\n  幂等（同 threadId+runId ×3）：${idempotent ? "PASS" : "FAIL"}`);
  for (const r of idempotencyRuns) {
    console.log(`    第 ${r.attempt} 次 status=${r.status} 事件 ${r.eventCount} 文本长度 ${r.textLength} ${r.durationMs}ms`);
  }
  console.log(
    `\n  RUN_STARTED.input 脱敏：${leakSessions.length > 0 ? `需要处理（${leakSessions.length}/${ok.length} 次会话明文回显输入）` : "未发现回显"}`,
  );
  console.log(`\n  原始输出：${OUTPUT}`);
  console.log(`  完成会话 ${ok.length}/${SESSIONS}`);
}

main().catch((error) => {
  console.error(`探针失败：${error.message}`);
  process.exit(1);
});
