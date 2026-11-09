#!/usr/bin/env node
/**
 * 零号冒烟 runner（OBL-M7 最小框架第 2 件，关闭 M0 T1.4 脚本欠账）。
 *
 * 判据口径：
 * - 三件套版本一致：题目文档 v2 / gold v2 / 本 runner；gold 的 contentHash 与文件声明、重算值三方比对；
 * - 只跑「确定性链路已上线」的题目（M4 阶段 14/20）；LLM 编排依赖题（澄清/注入/跨租户文本/discovery/多轮）
 *   一律 skip 且逐题写明依赖里程碑，skip 不算通过（评测统计口径 v1 §2）；
 * - 分层按 gold 的 expectedTerminal / riskType 分列，附 Wilson 95% 区间；
 * - 拒绝类题目当前判定为「业务错误码拒绝且非 500」；错误码与 gold reasonCode 的精确映射
 *   在 M5 澄清/拒答装配时接线后收紧（runner 注释与证据 JSON 双登记）。
 *
 * 用法：node scripts/verify/smoke-runner.mjs [--base-url http://127.0.0.1:8080] [--manifest-id <id>]
 */
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const GOLD_PATH = resolve(repo, "docs/06-verification/evaluation/零号冒烟集-gold-v2.json");
const DOC_PATH = resolve(repo, "docs/06-verification/evaluation/零号冒烟集-v2.md");
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

// ── 版本哈希三方校验（M0 T1.4 验收口径）───────────────────────────────
// contentHash 语义（gold v2，D63 修复）：gold 数组规范化 JSON 的 SHA-256，可复核、不含自身元数据。
const contentHash = sha256(
  Buffer.from(JSON.stringify(sortDeep(gold.gold)), "utf8"),
);
if (gold.contentHash !== `sha256:${contentHash}`) {
  console.error(`gold contentHash 不一致：文件声明 ${gold.contentHash}，实测 sha256:${contentHash}`);
  process.exit(1);
}
console.log(`gold contentHash 校验通过: ${contentHash.slice(0, 16)}…（${gold.gold.length} 题）`);

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
 * 题目 → 确定性链路 DSL（M4 能力内）。null = 依赖未上线能力，skip 并写明里程碑。
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
  // 行为校准（gold v1）：「销售额」已注册为 gmv 别名 → 预期直接执行；DSL 层验证 metricId 有效
  12: () => dslQuery("metric.gmv", [], "this_year"),
  13: null, // 「营收」口径解释 → 澄清装配（M5）
  // 手机号不在首发信息项 + 导出形状敏感：期望 OUT_OF_MANIFEST / 敏感 guardrail 拒绝
  14: () => dslLookup(["property.customer.phone"]),
  15: null, // 用户文本改租户 → 编排层身份边界（M5；RLS 已由 rls-probe 覆盖）
  16: null, // 提示注入拒绝 → Proposal 校验（M5）
  17: null, // OUT_OF_SCOPE 能力边界 → 编排层（M5）
  18: () => ({
    mode: "QUERY",
    subIntents: [
      { subIntentId: "s1", metricId: "metric.gmv", dimensions: ["dim.region"], timeExpressionRaw: "近 3650 天", timeRange: { field: "dim.order_date" } },
      { subIntentId: "s2", metricId: "metric.order_count", dimensions: ["dim.region"], timeExpressionRaw: "近 3650 天", timeRange: { field: "dim.order_date" } },
      { subIntentId: "s3", metricId: "metric.avg_order_value", dimensions: ["dim.region"], timeExpressionRaw: "近 3650 天", timeRange: { field: "dim.order_date" } },
    ],
  }),
  19: null, // 多轮指代 → 会话状态机（M5）
  20: null, // discovery → 终态装配（M5）
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

const results = [];
for (const item of gold.gold) {
  const factory = MAPPING[item.id];
  if (!factory) {
    results.push({
      id: item.id,
      status: "skip",
      expectedTerminal: item.expectedTerminal,
      riskType: item.riskType,
      reason: "依赖未上线能力（LLM 编排 / 会话 / discovery），M5 装配后回补",
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
    verdict: outcome.verdict,
    errorCode: outcome.errorCode ?? null,
    reason: outcome.errorMessage || outcome.detail || null,
  });
}

// ── 分层统计 + Wilson 95%（评测统计口径 v1 §3/§4）─────────────────────
const count = (layer, value) => {
  const rows = results.filter((item) => item[layer] === value);
  const pass = rows.filter((item) => item.status === "pass").length;
  return { n: rows.length, pass, skip: rows.filter((item) => item.status === "skip").length };
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
  };
})();

const evidence = {
  runId,
  ranAt: new Date().toISOString(),
  manifestId: MANIFEST_ID,
  runnerVersion: "smoke-runner-v1",
  setHashes: {
    "零号冒烟集-gold-v2.json": `sha256:${sha256(readFileSync(GOLD_PATH))}`,
    "零号冒烟集-v2.md": `sha256:${sha256(readFileSync(DOC_PATH))}`,
    contentHash: `sha256:${contentHash}`,
  },
  mappingNote:
    "拒绝类题目当前判定为「业务错误码拒绝且非 500」；错误码与 gold reasonCode（OUT_OF_MANIFEST 等）的精确映射在 M5 澄清/拒答装配接线后收紧。",
  results,
  summary,
};
writeFileSync(EVIDENCE_PATH, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");

for (const item of results) {
  const badge = { pass: "OK  ", fail: "FAIL", skip: "SKIP", error: "ERR " }[item.status];
  console.log(`  ${badge} #${item.id} [${item.expectedTerminal}] ${item.errorCode ?? item.reason ?? ""}`);
}
console.log(
  `smoke: ${summary.pass} pass / ${summary.fail} fail / ${summary.skip} skip` +
    (summary.successRate === null ? "" : `（成功率 ${summary.successRate}，Wilson95 ${summary.wilson95}）`),
);
process.exit(summary.fail > 0 || summary.error > 0 ? 1 : 0);
