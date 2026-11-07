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

/**
 * 五类终态的场景与判定。
 *
 * 判定以模型输出的 `terminal` 字段为准（prompt 已把它定义为显式终态标签），
 * 不再靠猜字段组合——否则同一份输出能被解释成多种终态，测量口径不稳定。
 *
 * 样本全部限定在候选集能力内：不拿「清单里没有客户维度」去考澄清，
 * 也不拿「清单没有客户数指标」去考复合拆分，那测的是清单而非模型。
 */
/**
 * 终态检测：优先采用模型声明的 terminal，缺省时从字段推断，并做一致性校验。
 *
 * 为什么要两套：prompt 定义了 terminal 字段，但实测模型只有 20% 的会话会输出它，
 * 且存在「同时声明 discovery 与 clarification」这种自相矛盾。若只认 terminal，
 * 会把「输出可用但没打标签」判成失败；若只推断字段，又会放过自相矛盾。
 *
 * 返回 { declared, inferred, terminal, conflict }：
 *   conflict 非空表示输出同时表达了互斥终态（如既 discovery 又 clarification），按 FAIL 计。
 */
function detectTerminal(payload) {
  const declared = typeof payload.terminal === "string" ? payload.terminal : null;
  const inferred = [];
  if (payload.out_of_capability === true) inferred.push("out_of_capability");
  if (payload.needs_discovery === true) inferred.push("discovery");
  if (payload.clarification && typeof payload.clarification === "object"
      && Object.keys(payload.clarification).length > 0) inferred.push("clarification");
  const subCount = Array.isArray(payload.sub_intents) ? payload.sub_intents.length : 0;
  if (subCount >= 2) inferred.push("composite");
  if (subCount === 1) inferred.push("query");

  // 互斥对：能同时成立的只有 query/composite 之外的两两组合
  const conflicting = inferred.filter((t) => t !== "query" && t !== "composite");
  const conflict = conflicting.length > 1 ? conflicting.join("+") : null;

  const terminal = declared ?? (inferred.length === 1 ? inferred[0] : null);
  return { declared, inferred, terminal, conflict };
}

const TERMINAL = (payload) => detectTerminal(payload).terminal;

const SCENARIOS = [
  {
    key: "clarification",
    label: "澄清四要素",
    expect: "clarification",
    questions: [
      "看看销售情况",
      "最近业绩怎么样",
      "帮我分析一下最近的数据",
      "数据好一些了吗",
    ],
    judge: (payload) => {
      if (TERMINAL(payload) !== "clarification") return false;
      const c = payload.clarification;
      if (!c || typeof c !== "object") return false;
      // 四要素至少要有歧义点与默认建议，否则等于「只问了一句你指的是什么」
      return Boolean(c.ambiguity) && Boolean(c.default_suggestion);
    },
    detail: (payload) => payload.clarification ?? null,
  },
  {
    key: "discovery",
    label: "能力发现",
    expect: "discovery",
    questions: [
      "你能查哪些数据",
      "有哪些指标可以用",
      "我可以用什么维度和分析方式",
      "系统支持哪些分析场景",
    ],
    judge: (payload) => {
      if (TERMINAL(payload) !== "discovery") return false;
      const d = payload.discovery;
      return Boolean(d) && typeof d === "object" && Array.isArray(d.metrics);
    },
    detail: (payload) => {
      const d = payload.discovery;
      return d ? { metrics: d.metrics?.length ?? 0, dimensions: d.dimensions?.length ?? 0 } : null;
    },
  },
  {
    key: "approval",
    label: "审批回传",
    expect: "approval",
    questions: [
      "导出租户A全部客户的手机号",
      "把所有客户的邮箱列出来",
      "查一下全部敏感字段的明细",
      "批量导出客户身份信息",
    ],
    // 审批流属 M1 能力，system prompt 无审批终态；单独统计、不计入成功率
    judge: () => false,
    applicable: false,
    detail: () => null,
  },
  {
    key: "composite",
    label: "复合子结果",
    expect: "composite",
    questions: [
      "华东和华南的 GMV 对比，同时看订单数",
      "按区域看成交金额，再按渠道看订单数",
      "上个月的 GMV 和这个月的订单数分别是多少",
      "分渠道统计 GMV，并给出各区域订单数",
    ],
    judge: (payload) =>
      TERMINAL(payload) === "composite"
      && Array.isArray(payload.sub_intents)
      && payload.sub_intents.length >= 2,
    detail: (payload) => ({ subIntentCount: payload.sub_intents?.length ?? 0 }),
  },
  {
    key: "reason_code",
    label: "原因码",
    expect: "out_of_capability",
    questions: [
      "预测下个季度的销量",
      "帮我写一份营销文案",
      "我们是否应该进入东南亚市场",
      "分析一下竞品的定价策略",
    ],
    judge: (payload) =>
      TERMINAL(payload) === "out_of_capability"
      && payload.out_of_capability === true
      && typeof payload.reason_code === "string"
      && payload.reason_code.length > 0,
    detail: (payload) => ({ reason_code: payload.reason_code }),
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

/**
 * 合法终态：只要产出了五类之一的可判定结构，就说明协议能承载并重建终态。
 * 与「和期望类别一致」是两件事——前者测协议/框架，后者测 prompt 与模型理解，
 * 混在一起会把 prompt 问题误判成框架不达标（反之亦然）。
 */
function isLegalTerminal(payload) {
  const declared = typeof payload.terminal === "string"
    && ["query", "composite", "clarification", "discovery", "out_of_capability"].includes(payload.terminal);
  if (declared) return true;
  // 兜底：未声明 terminal 时看是否给出任一可判定结构
  const hasClarification = payload.clarification && typeof payload.clarification === "object"
    && Object.keys(payload.clarification).length > 0;
  const hasComposite = Array.isArray(payload.sub_intents) && payload.sub_intents.length > 0;
  const hasReasonCode = payload.out_of_capability === true
    && typeof payload.reason_code === "string" && payload.reason_code.length > 0;
  return hasClarification || hasComposite || hasReasonCode || payload.needs_discovery === true;
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
      const detection = parsed.ok ? detectTerminal(parsed.value) : null;
      const passed = parsed.ok && !detection.conflict && scenario.judge(parsed.value);
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
        legalTerminal: parsed.ok ? isLegalTerminal(parsed.value) : false,
        terminal: detection?.terminal ?? null,
        terminalDeclared: detection?.declared ?? null,
        terminalInferred: detection?.inferred ?? [],
        terminalConflict: detection?.conflict ?? null,
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
  const legal = results.filter((r) => r.legalTerminal).length;
  const legalRate = results.length === 0 ? 0 : legal / results.length;
  const parsedCount = results.filter((r) => r.parsed).length;
  const declaredTerminal = results.filter((r) => r.terminalDeclared).length;
  const conflicts = results.filter((r) => r.terminalConflict).length;
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
    parsedJson: parsedCount,
    parsedJsonRate: Number((results.length === 0 ? 0 : parsedCount / results.length).toFixed(4)),
    declaredTerminalCount: declaredTerminal,
    terminalConflicts: conflicts,
    legalTerminalCount: legal,
    legalTerminalRate: Number(legalRate.toFixed(4)),
    byScenario,
    results,
    verdict: "FAIL",
    note: "successRate 是「与期望终态类别一致」的比率；legalTerminalRate 是「产出了任一合法终态」的比率。两者分开报告：前者含 prompt 理解，后者只反映协议能否承载。审批回传在 M0 无对应能力，不计入分母也不记为通过，因此 #3 整体仍为 FAIL",
  };

  mkdirSync(dirname(OUTPUT), { recursive: true });
  writeFileSync(OUTPUT, `${JSON.stringify(report, null, 2)}\n`, "utf8");

  console.log("\n  分类结果");
  for (const [key, stat] of Object.entries(byScenario)) {
    const tag = stat.applicable ? `${stat.passed}/${stat.total}` : "N/A（M0 无此能力）";
    console.log(`    ${stat.label.padEnd(12)} ${tag}`);
  }
  console.log(
    `\n  与期望类别一致：${(rate * 100).toFixed(1)}%（${passed}/${applicable.length} 次适用会话）`,
  );
  console.log(`  JSON 解析成功：${(report.parsedJsonRate * 100).toFixed(1)}%（${parsedCount}/${results.length}）`);
  console.log(`  产出合法终态：${(legalRate * 100).toFixed(1)}%（${legal}/${results.length}）`);
  console.log(`  模型显式声明 terminal：${((declaredTerminal / results.length) * 100).toFixed(1)}%（${declaredTerminal}/${results.length}）`);
  console.log(`  终态自相矛盾：${conflicts} 次（同时表达互斥终态，按 FAIL 计）`);
  console.log(`  判据：${report.verdict}（审批回传在 M0 不可测，五类无法全部覆盖）`);
  console.log(`  原始输出：${OUTPUT}`);
}

main().catch((e) => {
  console.error(`探针失败：${e.message}`);
  process.exit(1);
});
