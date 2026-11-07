/**
 * 「点名资产」机械闸门可行性验证（M1/T1.1，负责人要求：先验证、多用样例）。
 *
 * 背景：G2 批 1 残余 1 例失败——「最近业绩怎么样」模型输出契约完全合法的 query，
 * 契约校验拦不住（格式对、选择错），提示词三轮强化推不动。
 * 修复方案：检索瀑布第一级前置——问题未点名任何候选资产（name/alias 字面匹配）时，
 * query/composite 判违规进修复轮。
 *
 * 本脚本两阶段验证：
 *   阶段一（离线）：同一套样例在「仅业务名」与「业务名+试验别名」两套词表下的闸门判定，
 *                  量化误拦率与别名覆盖的作用；
 *   阶段二（真机）：全部样例走真实 AG-UI 通道，验证泛化问法被拦→修复→澄清、
 *                  点名问法不受影响、能力外/发现类不受闸门干扰。
 *
 * 运行：node scripts/poc/run-gate-feasibility.mjs [--base-url http://127.0.0.1:8080] [--skip-live]
 * 产物：docs/06-verification/poc/闸门可行性-raw.json
 */
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const argOf = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};
const BASE = argOf("--base-url", "http://127.0.0.1:8080");
const SKIP_LIVE = process.argv.includes("--skip-live");
const OUTPUT = resolve(argOf("--output", "docs/06-verification/poc/闸门可行性-raw.json"));

/** demo 清单词表（仅业务名，现状）。 */
const VOCAB_NAMES = [
  { id: "metric.gmv", name: "GMV" },
  { id: "metric.order_count", name: "订单数" },
  { id: "dim.region", name: "区域" },
  { id: "dim.channel", name: "渠道" },
  { id: "dim.order_date", name: "下单日期" },
];
/** 试验性扩展别名（演示别名机制价值；M1 起由业务化命名规格的 aliases 字段进清单）。 */
const ALIAS_EXTENSION = {
  "metric.gmv": ["成交金额", "成交额", "销售额", "流水"],
  "metric.order_count": ["单量", "下单数", "订单量"],
};

const vocabWith = (withAlias) =>
  VOCAB_NAMES.map((asset) => ({
    ...asset,
    aliases: withAlias ? (ALIAS_EXTENSION[asset.id] ?? []) : [],
  }));

const mentioned = (question, vocab) =>
  vocab.some((asset) =>
    [asset.name, ...asset.aliases].some((term) =>
      question.toLowerCase().includes(term.toLowerCase())));

/** 样例集：kind = gate-pass（点名，应放行）| gate-block（泛化，应拦）| free（闸门外，模型终态决定） */
const SAMPLES = [
  { q: "上个月的 GMV 是多少", kind: "gate-pass", expectTerminal: "query" },
  { q: "华东的成交金额", kind: "gate-pass", expectTerminal: "query" },
  { q: "按渠道统计订单数", kind: "gate-pass", expectTerminal: "query" },
  { q: "今年订单数有多少", kind: "gate-pass", expectTerminal: "query" },
  { q: "近 7 天的 GMV", kind: "gate-pass", expectTerminal: "query" },
  { q: "华南的 GMV 和订单数", kind: "gate-pass", expectTerminal: "composite" },
  { q: "各区域的成交金额和渠道订单数", kind: "gate-pass", expectTerminal: "composite" },
  { q: "订单数按区域分布", kind: "gate-pass", expectTerminal: "query" },
  { q: "最近业绩怎么样", kind: "gate-block", expectTerminal: "clarification" },
  { q: "看看销售情况", kind: "gate-block", expectTerminal: "clarification" },
  { q: "帮我分析一下", kind: "gate-block", expectTerminal: "clarification" },
  { q: "经营状况如何", kind: "gate-block", expectTerminal: "clarification" },
  { q: "数据表现怎么样", kind: "gate-block", expectTerminal: "clarification" },
  { q: "最近生意好不好", kind: "gate-block", expectTerminal: "clarification" },
  { q: "情况怎么样", kind: "gate-block", expectTerminal: "clarification" },
  { q: "业绩如何", kind: "gate-block", expectTerminal: "clarification" },
  { q: "预测下个季度的销量", kind: "free", expectTerminal: "out_of_capability" },
  { q: "员工工资有多少", kind: "free", expectTerminal: "out_of_capability" },
  { q: "竞对的价格是多少", kind: "free", expectTerminal: "out_of_capability" },
  { q: "明天天气如何", kind: "free", expectTerminal: "out_of_capability" },
  { q: "你能查哪些数据", kind: "free", expectTerminal: "discovery" },
  { q: "有哪些指标可以用", kind: "free", expectTerminal: "discovery" },
  { q: "有什么维度", kind: "free", expectTerminal: "discovery" },
  { q: "支持哪些分析方式", kind: "free", expectTerminal: "discovery" },
  { q: "卖了多少", kind: "gate-block", expectTerminal: "clarification", note: "别名机制检验：仅业务名词表会误拦" },
  { q: "成交额多少", kind: "gate-block", expectTerminal: "clarification", note: "同上；试验别名表下应放行为 query" },
];

// ── 阶段一：离线闸门判定 ─────────────────────────────────────────────
function offlinePhase() {
  const rows = [];
  for (const withAlias of [false, true]) {
    const vocab = vocabWith(withAlias);
    const tally = { pass: 0, block: 0, na: 0, overBlock: 0, missedBlock: 0 };
    for (const sample of SAMPLES) {
      const hit = mentioned(sample.q, vocab);
      const modelLikelyQuery = sample.kind !== "free" || sample.q.includes("销量");
      let gate;
      if (!hit && (sample.kind === "gate-pass" || (sample.kind === "gate-block"))) {
        gate = sample.kind === "gate-pass" ? "误拦" : "正确拦截";
        if (sample.kind === "gate-pass") tally.overBlock++;
        else tally.block++;
      } else if (hit && sample.kind === "gate-pass") {
        gate = "放行";
        tally.pass++;
      } else if (hit && sample.kind === "gate-block") {
        gate = "漏拦（词表命中但属泛化问法）";
        tally.missedBlock++;
      } else {
        gate = "n/a（free 类，闸门不介入）";
        tally.na++;
      }
      rows.push({ withAlias, question: sample.q, kind: sample.kind, hit, gate, note: sample.note ?? "" });
    }
    console.log(`阶段一[${withAlias ? "业务名+试验别名" : "仅业务名"}]：`
      + `放行 ${tally.pass} / 正确拦截 ${tally.block} / 误拦 ${tally.overBlock}`
      + ` / 漏拦 ${tally.missedBlock} / 不介入 ${tally.na}`);
  }
  return rows;
}

// ── 阶段二：真机端到端 ───────────────────────────────────────────────
async function ask(threadId, runId, question) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 180000);
  const messageTexts = new Map();
  let repairEvents = 0;
  try {
    const response = await fetch(`${BASE}/agent/run/ontos`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify({
        threadId, runId,
        messages: [{ id: `${runId}-m`, role: "user", content: question }],
        tools: [], context: [], state: {}, forwardedProps: {},
      }),
      signal: controller.signal,
    });
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
            if (event.type === "TEXT_MESSAGE_CONTENT") {
              const id = event.messageId ?? event.replyId ?? "unknown";
              messageTexts.set(id, (messageTexts.get(id) ?? "") + (event.delta ?? ""));
            }
            if (event.type === "CUSTOM"
                && (event.name === "intent_repair" || event.name === "intent_repair_exhausted")) {
              repairEvents++;
            }
          } catch { /* 忽略非 JSON 行 */ }
        }
      }
    }
  } catch (e) {
    return { messageTexts: [], repairEvents, error: String(e).slice(0, 120) };
  } finally {
    clearTimeout(timer);
  }
  return { messageTexts: [...messageTexts.values()], repairEvents, error: null };
}

function extractJson(text) {
  const candidate = text.trim();
  const start = candidate.indexOf("{");
  const end = candidate.lastIndexOf("}");
  if (start < 0 || end <= start) return null;
  try {
    return JSON.parse(candidate.slice(start, end + 1));
  } catch {
    return null;
  }
}

async function livePhase() {
  const rows = [];
  const tally = { pass: 0, fail: 0 };
  for (const [index, sample] of SAMPLES.entries()) {
    const response = await ask(`gate-${index}`, `gate-${index}`, sample.q);
    const blocks = response.messageTexts.map((text) => ({ text, json: extractJson(text) }));
    const lastValid = [...blocks].reverse().find((block) => block.json !== null);
    const terminal = lastValid?.json?.terminal ?? null;
    const gateFired = (response.repairEvents ?? 0) > 0;
    let met;
    if (sample.kind === "gate-block") {
      met = terminal === "clarification"; // 拦截后必须落到澄清
    } else {
      met = terminal === sample.expectTerminal;
    }
    met ? tally.pass++ : tally.fail++;
    rows.push({
      question: sample.q, kind: sample.kind, expect: sample.expectTerminal,
      modelTerminal: terminal, gateFired, repairEvents: response.repairEvents,
      messageBlocks: response.messageTexts.length, met, error: response.error,
    });
    console.log(`  [${met ? "PASS" : "FAIL"}] ${sample.q} → ${terminal ?? "无输出"}`
      + (gateFired ? `（修复轮 ${response.repairEvents}）` : ""));
  }
  console.log(`阶段二[真机]：${tally.pass}/${SAMPLES.length} 符合预期`);
  return { rows, tally };
}

const offlineRows = offlinePhase();
const live = SKIP_LIVE ? { skipped: true } : await livePhase();
const result = {
  generatedAt: new Date().toISOString(),
  purpose: "「点名资产」机械闸门可行性验证（检索瀑布第一级前置）",
  sampleCount: SAMPLES.length,
  offline: offlineRows,
  live,
};
writeFileSync(OUTPUT, JSON.stringify(result, null, 2));
console.log(`产物：${OUTPUT}`);
