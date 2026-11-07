#!/usr/bin/env node
/**
 * PoC #3 五类结构化终态端到端重建（M0 / W3 / T3.1 的最后一项判据）。
 *
 * 走真实 AG-UI 通道（POST {prefix}/run/{agentId}），解析 TEXT_MESSAGE_CONTENT 增量拼出的 JSON，
 * 判断五类终态能否被重建：
 *
 *   1. 澄清四要素  → clarification 非空
 *   2. discovery   → needs_discovery = true
 *   3. 审批回传    → 当前 system prompt 未定义该字段，M1 审批流才有，M0 记 NOT_APPLICABLE
 *   4. 复合子结果  → sub_intents.length >= 2
 *   5. 原因码      → out_of_capability = true 且 reason_code 非空
 *
 * 判据要求 20 次会话成功率 100%。不含审批回传的场景单独统计，不混入分子分母，
 * 也不因「反正验不了」就把它算成通过。
 *
 * 用法：
 *   node scripts/poc/run-poc03-terminal-states.mjs [--base-url ...] [--rounds 4]
 */
import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";

const argOf = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};

const BASE = argOf("--base-url", "http://127.0.0.1:8080");
const AGENT = argOf("--agent-id", "ontos");
const ROUNDS = Number(argOf("--rounds", "4"));
const TIMEOUT_MS = Number(argOf("--timeout", "180000"));
const OUTPUT = resolve(argOf("--output", "docs/06-verification/poc/PoC-03-终态重建-raw.json"));

/** 五类终态的场景与判定。 */
const SCENARIOS = [
  {
    key: "clarification",
    label: "澄清四要素",
    questions: [
      "看看销售情况",
      "最近业绩怎么样",
      "帮我分析一下客户",
      "数据好一些了吗",
    ],
    judge: (payload) =>
      payload.clarification !== null &&
      payload.clarification !== undefined &&
      typeof payload.clarification === "object" &&
      Object.keys(payload.clarification).length > 0,
    detail: (payload) => payload.clarification ?? null,
  },
  {
    key: "discovery",
    label: "能力发现",
    questions: [
      "你能查哪些数据",
      "有哪些指标可以用",
      "我可以用什么维度和分析方式",
      "系统支持哪些分析场景",
    ],
    judge: (payload) => payload.needs_discovery === true,
    detail: (payload) => ({ needs_discovery: payload.needs_discovery }),
  },
  {
    key: "approval",
    label: "审批回传",
    questions: [
      "导出租户A全部客户的手机号",
      "把所有客户的邮箱列出来",
      "查一下全部敏感字段的明细",
      "批量导出客户身份信息",
    ],
    // system prompt 未定义审批字段，M0 阶段不可能产生，单独统计、不计入成功率
    judge: () => false,
    applicable: false,
    detail: () => null,
  },
  {
    key: "composite",
    label: "复合子结果",
    questions: [
      "华东和华南的 GMV 对比，同时看订单数",
      "按区域看成交金额，再按渠道看客户数",
      "上个月的 GMV 和这个月的订单数分别是多少",
      "分渠道统计 GMV，并给出各区域客户数",
    ],
    judge: (payload) => Array.isArray(payload.sub_intents) && payload.sub_intents.length >= 2,
    detail: (payload) => ({ subIntentCount: payload.sub_intents?.length ?? 0 }),
  },
  {
    key: "reason_code",
    label: "原因码",
    questions: [
      "预测下个季度的销量",
      "帮我写一份营销文案",
      "我们是否应该进入东南亚市场",
      "分析一下竞品的定价策略",
    ],
    judge: (payload) =>
      payload.out_of_capability === true &&
      typeof payload.reason_code === "string" &&
      payload.reason_code.length > 0,
    detail: (payload) => ({ out_of_capability: payload.out_of_capability, reason_code: payload.reason_code }),
  },
];

async function ask(threadId, runId, question) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  const startedAt = Date.now();
  let text = "";
  let eventCount = 0;
  let status = 0;
  let error = null;

  try {
    const response = await fetch(`${BASE}/agent/run/${AGENT}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify({
        threadId,
        runId,
        messages: [{ id: `${runId}-m`, role: "user", content: question }],
        tools: [],
        context: [],
        state: {},
        forwardedProps: {},
      }),
      signal: controller.signal,
    });
    status = response.status;
    if (!response.ok || !response.body) {
      error = `HTTP ${status}`;
      return { status, text, eventCount, error, durationMs: Date.now() - startedAt };
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
          try {
            const event = JSON.parse(line.slice(5).trim());
            eventCount++;
            if (event.type === "TEXT_MESSAGE_CONTENT") text += event.delta ?? "";
          } catch {
            /* 忽略非 JSON 行 */
          }
        }
      }
    }
  } catch (e) {
    error = String(e).slice(0, 200);
  } finally {
    clearTimeout(timer);
  }
  return { status, text, eventCount, error, durationMs: Date.now() - startedAt };
}

/** 从模型输出里提取 JSON（可能被 ``` 包裹或带前后缀说明）。 */
function extractJson(text) {
  const fenced = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  const candidate = (fenced ? fenced[1] : text).trim();
  const start = candidate.indexOf("{");
  const end = candidate.lastIndexOf("}");
  if (start < 0 || end <= start) return { ok: false, reason: "未找到 JSON 对象" };
  try {
    return { ok: true, value: JSON.parse(candidate.slice(start, end + 1)) };
  } catch (e) {
    return { ok: false, reason: `JSON 解析失败：${e.message}` };
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  console.log(`PoC #3 五类终态重建 → ${BASE}/agent/run/${AGENT}`);
  const results = [];

  for (const scenario of SCENARIOS) {
    for (let i = 0; i < ROUNDS; i++) {
      const question = scenario.questions[i % scenario.questions.length];
      const runId = `${scenario.key}-${i + 1}`;
      const response = await ask(`t-${runId}`, runId, question);
      const parsed = response.error ? { ok: false, reason: response.error } : extractJson(response.text);
      const passed = parsed.ok && scenario.judge(parsed.value);
      results.push({
        scenario: scenario.key,
        label: scenario.label,
        applicable: scenario.applicable !== false,
        round: i + 1,
        question,
        status: response.status,
        durationMs: response.durationMs,
        eventCount: response.eventCount,
        parsed: parsed.ok,
        parseReason: parsed.ok ? null : parsed.reason,
        passed,
        detail: parsed.ok ? scenario.detail(parsed.value) : null,
        textPreview: response.text.slice(0, 300),
      });
      console.log(
        `  [${scenario.key} ${i + 1}/${ROUNDS}] ${passed ? "PASS" : "FAIL"} ${response.durationMs}ms` +
          (parsed.ok ? "" : ` (${parsed.reason})`),
      );
      await sleep(300);
    }
  }

  const applicable = results.filter((r) => r.applicable);
  const passed = applicable.filter((r) => r.passed).length;
  const rate = applicable.length === 0 ? 0 : passed / applicable.length;
  const byScenario = Object.fromEntries(
    SCENARIOS.map((s) => {
      const rows = results.filter((r) => r.scenario === s.key);
      return [
        s.key,
        {
          label: s.label,
          applicable: s.applicable !== false,
          total: rows.length,
          passed: rows.filter((r) => r.passed).length,
          reasons: rows.filter((r) => !r.passed).map((r) => r.parseReason ?? "终态判定未通过"),
        },
      ];
    }),
  );

  const report = {
    generatedAt: new Date().toISOString(),
    scope: "PoC #3 五类结构化终态（经 AG-UI 端到端重建）；审批回传在 M0 无对应能力，单独统计",
    baseUrl: BASE,
    agentId: AGENT,
    roundsPerScenario: ROUNDS,
    totalSessions: results.length,
    applicableSessions: applicable.length,
    passed: passed,
    successRate: Number(rate.toFixed(4)),
    byScenario,
    results,
    verdict: rate === 1 && applicable.length >= 20 ? "PASS" : "FAIL",
    note: "审批回传（approval）不在 system prompt 的输出契约内，属 M1 审批流能力；本报告不把它计入分母，也不记为通过",
  };

  mkdirSync(dirname(OUTPUT), { recursive: true });
  writeFileSync(OUTPUT, `${JSON.stringify(report, null, 2)}\n`, "utf8");

  console.log("\n  分类结果");
  for (const [key, stat] of Object.entries(byScenario)) {
    const tag = stat.applicable ? `${stat.passed}/${stat.total}` : "N/A（M0 无此能力）";
    console.log(`    ${stat.label.padEnd(12)} ${tag}`);
  }
  console.log(
    `\n  适用会话成功率 ${(rate * 100).toFixed(1)}%（${passed}/${applicable.length}），共 ${results.length} 次会话`,
  );
  console.log(`  判据（100%）：${report.verdict}`);
  console.log(`  原始输出：${OUTPUT}`);
}

main().catch((e) => {
  console.error(`探针失败：${e.message}`);
  process.exit(1);
});
