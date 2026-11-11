#!/usr/bin/env node
/**
 * 零号冒烟 runner v2（M6/W5 T5.1 G-9 收敛：6 条 skip 题转实测 + 拒绝映射收紧）。
 *
 * v2 相对 v1 的变化：
 * - gold 升版 v3（[零号冒烟集-gold-v3.json]）：#13/#15/#16/#17/#19/#20 六题经
 *   /api/v1/ask/runs 编排链路真跑（ontos.agent.enabled=false 确定性降级），不再 skip；
 * - 拒绝类判定从「业务错误码拒绝且非 500」收紧为逐题精确断言（终态 + reasonCode），
 *   注入题断言 PROMPT_INJECTION_SUSPECTED（T5.3 第三项：错误码 ↔ gold reasonCode 精确映射）；
 * - 评测固定于确定性降级路径：语义级判定（OUT_OF_SCOPE 文案、身份边界拒答、多轮完整解析）
 *   属 LLM 路径能力，降级路径按安全底线判据（不执行/不泄露/有审计/有引导）判定，
 *   gold v3 逐题写明校准理由（v2 归档 99-archive）。
 *
 * 判据口径：
 * - 三件套版本一致：题目文档 v3 / gold v3 / 本 runner；gold 的 contentHash 与文件声明、重算值三方比对；
 * - 14 题 DSL 直跑（dry-run / composite），6 题 ask 实测（多轮题 #19 两轮会话）；
 * - 分层按 gold 的 expectedTerminal / riskType 分列，附 Wilson 95% 区间；skip 不算通过。
 *
 * 用法：node scripts/verify/smoke-runner.mjs [--base-url http://127.0.0.1:8080] [--manifest-id <id>]
 */
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const GOLD_PATH = resolve(repo, "docs/06-verification/evaluation/零号冒烟集-gold-v3.json");
const DOC_PATH = resolve(repo, "docs/06-verification/evaluation/零号冒烟集-v3.md");
const EVIDENCE_PATH = resolve(repo, "docs/06-verification/evaluation/零号冒烟-run-latest.json");
const BASE = process.argv.includes("--base-url")
  ? process.argv[process.argv.indexOf("--base-url") + 1]
  : "http://127.0.0.1:8080";

const sha256 = (buffer) => createHash("sha256").update(buffer).digest("hex");

/** 与生成侧一致的规范化：键排序 + 紧凑分隔符（评测统计口径 v1 §5 哈希语义）。 */
const sortDeep = (value) => {
  if (Array.isArray(value)) return value.map(sortDeep);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.keys(value).sort().map((key) => [key, sortDeep(value[key])]),
    );
  }
  return value;
};

const gold = JSON.parse(readFileSync(GOLD_PATH, "utf8"));
if (gold.version !== "v3") {
  console.error(`gold 版本不是 v3：${gold.version}（runner v2 与 gold v3 配套，勿混用）`);
  process.exit(1);
}

// ── 版本哈希三方校验（M0 T1.4 验收口径）───────────────────────────────
// contentHash 语义（gold v3，D63 修复沿用）：gold 数组规范化 JSON 的 SHA-256，可复核、不含自身元数据。
const contentHash = sha256(
  Buffer.from(JSON.stringify(sortDeep(gold.gold)), "utf8"),
);
if (gold.contentHash !== `sha256:${contentHash}`) {
  console.error(`gold contentHash 不一致：文件声明 ${gold.contentHash}，实测 sha256:${contentHash}`);
  process.exit(1);
}
console.log(`gold contentHash 校验通过: ${contentHash.slice(0, 16)}…（${gold.gold.length} 题）`);

// M6 全量深度审核 D-8：三方复算——读取题目文档 §7 声明并与磁盘字节/规范化数组比对。
// 原实现只做「文件声明 vs 重算」两方，题目文档声明从未参与断言（口径夸大）。
const docText = readFileSync(DOC_PATH, "utf8");
const declaredFileHash = docText.match(/零号冒烟集-gold-v3\.json\) \| `([0-9a-f]{16})/)?.[1];
const declaredContentHash = docText.match(/contentHash `sha256:([0-9a-f]{16})/)?.[1];
const actualFileHash = sha256(readFileSync(GOLD_PATH));
if (!declaredFileHash || !declaredContentHash) {
  console.error("题目文档 §7 冻结哈希声明缺失，无法三方比对");
  process.exit(1);
}
if (!actualFileHash.startsWith(declaredFileHash)
    || !contentHash.startsWith(declaredContentHash)) {
  console.error(`题目文档声明与磁盘不一致: 文件 ${declaredFileHash} vs ${actualFileHash.slice(0, 16)}；`
    + `contentHash ${declaredContentHash} vs ${contentHash.slice(0, 16)}`);
  process.exit(1);
}
console.log(`题目文档声明比对通过（三方一致）: ${declaredFileHash} / ${declaredContentHash}…`);

const HEADERS = {
  "Content-Type": "application/json",
  "X-Ontos-Tenant": "demo",
  "X-Ontos-User": "smoke-runner",
  "X-Ontos-Session": `smoke-${Date.now()}`,
  "X-Ontos-Turn": "1",
};

let MANIFEST_ID = process.argv.includes("--manifest-id")
  ? process.argv[process.argv.indexOf("--manifest-id") + 1]
  : null;
if (!MANIFEST_ID) {
  MANIFEST_ID = await fetch(`${BASE}/api/v1/ontology/manifests/current`, { headers: HEADERS })
    .then((response) => response.json())
    .then((body) => body?.data?.manifestId);
  if (!MANIFEST_ID) {
    console.error("无法取得当前生效清单，请用 --manifest-id 显式指定");
    process.exit(1);
  }
}

const TIME_EXPRESSION = {
  last_month: "上个月",
  this_month: "这个月",
  this_year: "今年",
  last_quarter: "上季度",
  last_7_days: "近 7 天",
};

const ENTITY = "concept.customer";

/**
 * DSL 直跑题（via=dsl，14 题）：题目 → 确定性链路 DSL（M2–M4 能力内）。
 * 映射只按 gold.expectedIntent 的结构化期望构造，不做自然语言理解。
 */
const MAPPING = {
  1: () => dslQuery("metric.gmv", ["dim.region"], "last_month"),
  2: () => dslQuery("metric.order_count", ["dim.channel"]),
  3: () => dslQuery("metric.gmv", [], "this_year"),
  4: () => ({
    mode: "QUERY",
    metricId: "metric.gmv",
    dimensions: [],
    filters: [{ fieldId: "dim.region", operator: "EQ", values: ["华东"] }],
    timeExpressionRaw: "近 3650 天",
    timeRange: { field: "dim.order_date" },
  }),
  // 「注册时间」不在首发信息项：期望 OUT_OF_MANIFEST 类拒绝（属性不存在）
  5: () => dslLookup(["property.customer.register_time"]),
  6: () => ({
    mode: "LOOKUP_BATCH",
    entityId: ENTITY,
    identityPropertyId: "property.customer.customer_id",
    attributes: ["property.customer.tier"],
    identities: ["c-1001", "c-9999"],
  }),
  7: () => ({
    mode: "LOOKUP",
    entityId: "concept.order",
    attributes: ["property.order.amount"],
    identity: { propertyId: "property.order.order_no", value: "o-1" },
  }),
  8: () => ({
    mode: "LOOKUP",
    entityId: ENTITY,
    attributes: ["property.customer.tier"],
    identity: { propertyId: "property.customer.customer_id", value: "c-1001" },
    booleanMetricId: "metric.high_value_customer",
  }),
  9: () => dslQuery("metric.order_count", [], "last_7_days"),
  10: () => dslQuery("metric.gmv", ["dim.channel"], "last_quarter"),
  11: () => ({
    mode: "QUERY",
    metricId: "metric.gmv",
    dimensions: [],
    explicitRange: { field: "dim.order_date", from: "2026-01-01T00:00:00+08:00", to: "2026-01-31T23:59:59+08:00" },
  }),
  // 行为校准（gold v2）：「销售额」已注册为 gmv 别名 → 预期直接执行；DSL 层验证 metricId 有效
  12: () => dslQuery("metric.gmv", [], "this_year"),
  // 手机号不在首发信息项 + 导出形状敏感：期望 OUT_OF_MANIFEST / 敏感 guardrail 拒绝
  14: () => dslLookup(["property.customer.phone"]),
  18: () => ({
    mode: "QUERY",
    subIntents: [
      { subIntentId: "s1", metricId: "metric.gmv", dimensions: ["dim.region"], timeExpressionRaw: "近 3650 天", timeRange: { field: "dim.order_date" } },
      { subIntentId: "s2", metricId: "metric.order_count", dimensions: ["dim.region"], timeExpressionRaw: "近 3650 天", timeRange: { field: "dim.order_date" } },
      { subIntentId: "s3", metricId: "metric.avg_order_value", dimensions: ["dim.region"], timeExpressionRaw: "近 3650 天", timeRange: { field: "dim.order_date" } },
    ],
  }),
};

function dslQuery(metricId, dimensions, time) {
  const dsl = { mode: "QUERY", metricId, dimensions };
  if (time) {
    dsl.timeExpressionRaw = TIME_EXPRESSION[time];
    dsl.timeRange = { field: "dim.order_date" };
  }
  return dsl;
}

function dslLookup(attributes) {
  return {
    mode: "LOOKUP",
    entityId: ENTITY,
    attributes,
    identity: { propertyId: "property.customer.customer_id", value: "c-1001" },
  };
}

/** 每题唯一请求 ID：request_id 是幂等键，复用会被终态拦在入口。 */
const runId = `s${Date.now()}`;
let seq = 0;
const nextRequestId = () => `smoke-${runId}-${++seq}`;

/** 编译或拒绝信息；复合题走专用端点。 */
async function probe(dsl) {
  const endpoint = dsl.subIntents ? "/api/v1/query/composite/execute" : "/api/v1/query/dry-run";
  const response = await fetch(BASE + endpoint, {
    method: "POST",
    headers: { ...HEADERS, "X-Request-Id": nextRequestId() },
    body: JSON.stringify({ manifestId: MANIFEST_ID, ...dsl }),
  });
  const payload = await response.json();
  if (response.status >= 500) {
    return { verdict: "error", detail: `HTTP ${response.status}` };
  }
  if (payload.code === 0) {
    return { verdict: "ok" };
  }
  return { verdict: "rejected", errorCode: payload.code, errorMessage: payload.msg ?? "" };
}

/**
 * ask 实测题（via=ask，6 题）：经编排链路（/api/v1/ask/runs）真跑，确定性降级路径。
 * 每题独立 user + session（不串会话状态）；#19 两轮共享 session。
 */
async function probeAsk(item) {
  const user = `smoke-${runId}-q${item.id}`;
  const session = `sess-q${item.id}`;
  const askHeaders = (turn) => ({
    "Content-Type": "application/json",
    "X-Ontos-Tenant": "demo",
    "X-Ontos-User": user,
    "X-Ontos-Session": session,
    "X-Ontos-Turn": String(turn),
  });
  const ask = async (utterance, turn) => {
    const response = await fetch(`${BASE}/api/v1/ask/runs`, {
      method: "POST",
      headers: askHeaders(turn),
      body: JSON.stringify({ utterance }),
    });
    const payload = await response.json();
    if (response.status >= 500) {
      return { verdict: "error", detail: `HTTP ${response.status}` };
    }
    if (payload.code !== 0) {
      return { verdict: "rejected", errorCode: payload.code, errorMessage: payload.msg ?? "" };
    }
    return {
      verdict: "ok",
      terminal: payload.data?.terminal ?? null,
      reasonCode: payload.data?.reasonCode ?? null,
    };
  };

  // 多轮前导轮（#19 T1：建立会话锚点，必须 query）
  if (item.askPrelude) {
    const prelude = await ask(item.askPrelude.utterance, 1);
    if (prelude.verdict !== "ok" || prelude.terminal !== item.askPrelude.expectedTerminal) {
      return {
        verdict: "fail-prelude",
        detail: `前导轮期望 ${item.askPrelude.expectedTerminal}，实测 ${prelude.terminal ?? prelude.errorCode}`,
      };
    }
    const main = await ask(item.askUtterance, 2);
    return main;
  }
  return ask(item.askUtterance, 1);
}

/** via=ask 题的逐题精确判据（拒绝映射收紧，T5.3 第三项）。 */
function askPass(item, outcome) {
  if (outcome.verdict !== "ok") return false;
  if (outcome.terminal !== item.expectedTerminal) return false;
  // gold 声明了 expectedReasonCode → 精确断言（#16 PROMPT_INJECTION_SUSPECTED）
  if (item.expectedReasonCode && outcome.reasonCode !== item.expectedReasonCode) return false;
  // 安全底线判据：safetyBottomLine=no_execution 的题（#15/#17）终态非 query/composite 已由
  // expectedTerminal 断言覆盖（clarification/discovery 本身不执行），无需额外检查。
  return true;
}

const results = [];
for (const item of gold.gold) {
  if (item.via === "ask") {
    const outcome = await probeAsk(item);
    const pass = askPass(item, outcome);
    results.push({
      id: item.id,
      status: pass ? "pass" : "fail",
      expectedTerminal: item.expectedTerminal,
      riskType: item.riskType,
      via: "ask",
      verdict: outcome.verdict,
      terminal: outcome.terminal ?? null,
      reasonCode: outcome.reasonCode ?? outcome.errorCode ?? null,
      reason: outcome.detail ?? outcome.errorMessage ?? null,
    });
    continue;
  }
  const factory = MAPPING[item.id];
  if (!factory) {
    results.push({
      id: item.id,
      status: "skip",
      expectedTerminal: item.expectedTerminal,
      riskType: item.riskType,
      via: "dsl",
      reason: "无 DSL 映射（runner 配置缺口，不允许静默 skip）",
    });
    continue;
  }
  const outcome = await probe(factory());
  const pass =
    outcome.verdict === "ok"
      ? ["query", "composite"].includes(item.expectedTerminal)
      : outcome.verdict === "rejected"
        ? ["out_of_capability", "rejected", "clarification"].includes(item.expectedTerminal)
        : false;
  results.push({
    id: item.id,
    status: pass ? "pass" : "fail",
    expectedTerminal: item.expectedTerminal,
    riskType: item.riskType,
    via: "dsl",
    verdict: outcome.verdict,
    errorCode: outcome.errorCode ?? null,
    reason: outcome.errorMessage || outcome.detail || null,
  });
}

// ── 分层统计 + Wilson 95%（评测统计口径 v1 §3/§4）─────────────────────
const count = (layer, value) => {
  const rows = results.filter((item) => item[layer] === value);
  const pass = rows.filter((item) => item.status === "pass").length;
  const skip = rows.filter((item) => item.status === "skip").length;
  const error = rows.filter((item) => item.status === "error").length;
  // M6 全量深度审核 D-9：n = 有效样本（pass+fail），skip/error 不进分母（统计口径 v1 §2/§4）
  return { n: rows.length - skip - error, pass, skip, error };
};
const wilson = (pass, n) => {
  if (n === 0) return null;
  const z = 1.96;
  const p = pass / n;
  const denom = 1 + (z * z) / n;
  const center = (p + (z * z) / (2 * n)) / denom;
  const half = (z * Math.sqrt((p * (1 - p)) / n + (z * z) / (4 * n * n))) / denom;
  return [Number((center - half).toFixed(4)), Number((center + half).toFixed(4))];
};
const summary = (() => {
  const pass = results.filter((item) => item.status === "pass").length;
  const fail = results.filter((item) => item.status === "fail").length;
  const valid = pass + fail;
  return {
    pass,
    fail,
    skip: results.filter((item) => item.status === "skip").length,
    error: results.filter((item) => item.status === "error").length,
    validSamples: valid,
    successRate: valid === 0 ? null : Number((pass / valid).toFixed(4)),
    wilson95: wilson(pass, valid),
    byExpectedTerminal: Object.fromEntries(
      [...new Set(results.map((item) => item.expectedTerminal))].map((value) => [
        value,
        { ...count("expectedTerminal", value), wilson95: wilson(count("expectedTerminal", value).pass, count("expectedTerminal", value).n) },
      ]),
    ),
    byRiskType: Object.fromEntries(
      [...new Set(results.map((item) => item.riskType))].map((value) => [
        value,
        count("riskType", value),
      ]),
    ),
    byChannel: {
      dsl: count("via", "dsl"),
      ask: count("via", "ask"),
    },
  };
})();

// ── 失败样本落库（失败看板数据流：runner → 管理面 API → 五维下钻，T5.1 交付物 ④）──
const failureRows = results
  .filter((item) => item.status === "fail")
  .map((item) => ({
    questionId: `smoke-#${item.id}`,
    scenarioEntryMode: "none",
    queryShape: item.expectedTerminal,
    turnType: item.via === "ask" ? "single" : "single",
    isComposite: item.expectedTerminal === "composite",
    riskType: item.riskType,
    expectedTerminal: item.expectedTerminal,
    actualTerminal: item.terminal ?? (item.errorCode ? `code:${item.errorCode}` : item.verdict ?? null),
    errorCode: item.errorCode ?? null,
    reason: item.reason ?? null,
  }));
let pushResult = { pushed: 0, skipped: true, note: "无失败样本（看板空态）" };
if (failureRows.length > 0) {
  try {
    const pushResponse = await fetch(`${BASE}/api/v1/evaluation/failure-samples`, {
      method: "POST",
      headers: { ...HEADERS },
      body: JSON.stringify({ setId: "smoke-v3", runId, samples: failureRows }),
    });
    const pushPayload = await pushResponse.json();
    pushResult = { pushed: pushPayload.data?.inserted ?? 0, skipped: false };
  } catch (error) {
    pushResult = { pushed: 0, skipped: true, note: `落库失败（看板不可达，评测结论不受影响）: ${error.message}` };
  }
}

const evidence = {
  runId,
  ranAt: new Date().toISOString(),
  manifestId: MANIFEST_ID,
  runnerVersion: "smoke-runner-v2",
  agentMode: "ontos.agent.enabled=false（确定性降级路径）",
  setHashes: {
    "零号冒烟集-gold-v3.json": `sha256:${sha256(readFileSync(GOLD_PATH))}`,
    "零号冒烟集-v3.md": `sha256:${sha256(readFileSync(DOC_PATH))}`,
    contentHash: `sha256:${contentHash}`,
  },
  mappingNote:
    "v2 收敛（G-9）：6 条 skip 题经 /api/v1/ask/runs 实测（via=ask），拒绝类映射收紧为逐题精确终态 + reasonCode 断言；" +
    "#16 断言 PROMPT_INJECTION_SUSPECTED；#15/#17 按安全底线判据校准（gold v3 逐题写明）；DSL 题 14 题口径与 v1 一致。",
  dashboardPush: pushResult,
  results,
  summary,
};
writeFileSync(EVIDENCE_PATH, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");

for (const item of results) {
  const badge = { pass: "OK  ", fail: "FAIL", skip: "SKIP", error: "ERR " }[item.status];
  const detail = item.reasonCode ?? item.terminal ?? item.errorCode ?? item.reason ?? "";
  console.log(`  ${badge} #${item.id} [${item.expectedTerminal}] via=${item.via} ${detail}`);
}
console.log(
  `smoke: ${summary.pass} pass / ${summary.fail} fail / ${summary.skip} skip` +
    (summary.successRate === null ? "" : `（成功率 ${summary.successRate}，Wilson95 ${summary.wilson95}）`),
);
process.exit(summary.fail > 0 || summary.error > 0 || summary.skip > 0 ? 1 : 0);
