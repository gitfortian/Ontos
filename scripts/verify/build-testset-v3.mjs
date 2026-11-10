#!/usr/bin/env node
/**
 * test 集 v3 生成器（M6/W5 T5.1：E-1 场景入口题 ≥15 + E-2 增量题补至 ≥30）。
 *
 * 输入：test集-v2.json（241 题，sha256 556e564c…，只读）
 * 输出：test集-v3.json（271 题）
 * 新增 30 题：
 * - sm-manual-01..05   场景 manual 入口（DSL 直传，dry-run）
 * - sm-cold-01..05     场景 cold_start 入口（ask 编排流：hint 推荐 → 确认/退出）
 * - sm-inh-01..05      场景 inherit 沿用（ask 编排流：进场景后多轮）
 * - t-turn-incremental-16..30  多轮增量补齐（E-2：15 → 30）
 * 分层重算；哈希按磁盘字节复算（D-3 修复语义）。
 *
 * 用法：node scripts/verify/build-testset-v3.mjs
 */
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const SRC = resolve(repo, "docs/06-verification/evaluation/test集-v2.json");
const OUT = resolve(repo, "docs/06-verification/evaluation/test集-v3.json");

const base = JSON.parse(readFileSync(SRC, "utf8"));
if (base.version !== "v2") {
  throw new Error(`输入不是 test 集 v2：${base.version}`);
}

const FORBIDDEN_DEFAULT = ["自由 SQL", "物理表名", "绝对日期（LLM 产出）"];

/** manual 入口（query_shape=scenario_mode，DSL dry-run）。期望与 2026-11-10 真服务探针一致。 */
const smManual = [
  {
    id: "sm-manual-01", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "single", risk_type: "normal", scenario_entry_mode: "manual",
    question: "销售分析场景里看上个月各渠道的成交金额",
    dsl: { mode: "QUERY", scenarioId: "scenario.sales", metricId: "metric.gmv", dimensions: ["dim.channel"], timeExpressionRaw: "上个月" },
    expected: { terminal: "query", scenarioId: "scenario.sales", scopeNarrowed: true },
    necessary: ["场景授权校验通过", "scope 内指标/维度编译执行"],
    forbidden: FORBIDDEN_DEFAULT,
  },
  {
    id: "sm-manual-02", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "single", risk_type: "normal", scenario_entry_mode: "manual",
    question: "销售分析场景里看近 30 天各客户等级的客单价",
    dsl: { mode: "QUERY", scenarioId: "scenario.sales", metricId: "metric.avg_order_value", dimensions: ["dim.customer_tier"], timeExpressionRaw: "近 30 天" },
    expected: { terminal: "query", scenarioId: "scenario.sales", scopeNarrowed: true },
    necessary: ["场景授权校验通过", "scope 内指标/维度编译执行"],
    forbidden: FORBIDDEN_DEFAULT,
  },
  {
    id: "sm-manual-03", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "single", risk_type: "normal", scenario_entry_mode: "manual",
    question: "销售分析场景里看今年的订单数",
    dsl: { mode: "QUERY", scenarioId: "scenario.sales", metricId: "metric.order_count", dimensions: [], timeExpressionRaw: "今年" },
    expected: { terminal: "query", scenarioId: "scenario.sales", scopeNarrowed: true },
    necessary: ["场景授权校验通过", "scope 内指标编译执行"],
    forbidden: FORBIDDEN_DEFAULT,
  },
  {
    id: "sm-manual-04", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "single", risk_type: "normal", scenario_entry_mode: "manual",
    question: "切换到一个不存在的场景查成交金额",
    dsl: { mode: "QUERY", scenarioId: "scenario.nonexistent", metricId: "metric.gmv", dimensions: [], timeExpressionRaw: "上个月" },
    expected: { terminal: "out_of_capability", errorCode: 1004000003, note: "Scenario 不可用（未发布/无权限/已失效）——授权 + 已发布校验前置（ADR-0017 D1）" },
    necessary: ["拒绝", "错误码 1004000003", "不执行任何查询"],
    forbidden: FORBIDDEN_DEFAULT,
  },
  {
    id: "sm-manual-05", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "single", risk_type: "normal", scenario_entry_mode: "manual",
    question: "销售分析场景里按商品品类看订单数",
    dsl: { mode: "QUERY", scenarioId: "scenario.sales", metricId: "metric.order_count", dimensions: ["dim.product_category"], timeExpressionRaw: "上个月" },
    expected: { terminal: "out_of_capability", errorCode: 1004000007, note: "引用资产超出场景范围——场景边界独立于授权边界，只收窄不放宽" },
    necessary: ["拒绝", "错误码 1004000007（SCENARIO_SCOPE_VIOLATION）"],
    forbidden: FORBIDDEN_DEFAULT,
  },
];

/** cold_start 入口（ask 编排流：T1 hint 命中推荐 → T2 确认/退出；判据与真服务探针一致）。 */
const smCold = [
  {
    id: "sm-cold-01", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "single", risk_type: "normal", scenario_entry_mode: "cold_start",
    question: "订单趋势怎么样",
    flow: [{ turn: 1, utterance: "订单趋势怎么样" }],
    expected: { terminal: "clarification", scenarioCandidate: "scenario:scenario.sales", note: "entry_hints 唯一命中 + recommend_or_confirm → 推荐确认候选（不自动进入、不执行）" },
    necessary: ["不执行", "澄清候选含场景推荐（scenario:scenario.sales）"],
    forbidden: FORBIDDEN_DEFAULT,
  },
  {
    id: "sm-cold-02", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "single", risk_type: "normal", scenario_entry_mode: "cold_start",
    question: "订单趋势怎么样 →（确认进入）好",
    flow: [
      { turn: 1, utterance: "订单趋势怎么样" },
      { turn: 2, utterance: "好", clarificationChoice: "scenario:scenario.sales" },
    ],
    expected: { terminal: "discovery", note: "确认后进入场景；本轮话术无资产命中 → 场景内能力发现承接（T51-1 修复：场景确认不强制 query 组装）" },
    necessary: ["场景状态迁移为场景内", "无命中话术落 discovery 不落错误"],
    forbidden: FORBIDDEN_DEFAULT,
  },
  {
    id: "sm-cold-03", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "single", risk_type: "normal", scenario_entry_mode: "cold_start",
    question: "订单趋势怎么样 →（确认进入）看看销售额",
    flow: [
      { turn: 1, utterance: "订单趋势怎么样" },
      { turn: 2, utterance: "好", clarificationChoice: "scenario:scenario.sales" },
      { turn: 3, utterance: "看看销售额" },
    ],
    expected: { terminal: "query", note: "确认后场景内查询，scope 收窄执行（别名销售额 → metric.gmv ∈ semanticScope）" },
    necessary: ["场景内 query 执行成功"],
    forbidden: FORBIDDEN_DEFAULT,
  },
  {
    id: "sm-cold-04", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "single", risk_type: "normal", scenario_entry_mode: "cold_start",
    question: "订单趋势怎么样 →（选退出）留在自由问数 → 看看销售额",
    flow: [
      { turn: 1, utterance: "订单趋势怎么样" },
      { turn: 2, utterance: "留在自由问数", clarificationChoice: "scenario:__exit__" },
      { turn: 3, utterance: "看看销售额" },
    ],
    expected: { terminal: "query", note: "退出候选确认 → 不进入场景（FREE）；后续自由问数正常执行" },
    necessary: ["不进入场景", "自由问数 query 正常"],
    forbidden: FORBIDDEN_DEFAULT,
  },
  {
    id: "sm-cold-05", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "single", risk_type: "normal", scenario_entry_mode: "cold_start",
    question: "订单趋势怎么样 →（确认进入）近 7 天各渠道成交金额",
    flow: [
      { turn: 1, utterance: "订单趋势怎么样" },
      { turn: 2, utterance: "好", clarificationChoice: "scenario:scenario.sales" },
      { turn: 3, utterance: "近 7 天各渠道成交金额" },
    ],
    expected: { terminal: "query", vqrHit: "vqr.sales.daily_gmv_by_channel", note: "coldStartSamples 承接：VQR questionPattern 全串命中 → 模板参数化执行（仍走 execute 全链重校验）" },
    necessary: ["VQR 模板命中执行", "场景内 scope 一致"],
    forbidden: FORBIDDEN_DEFAULT,
  },
];

/** inherit 沿用（ask 编排流：进场景后的后续轮；判据与真服务探针一致）。 */
const smInh = [
  {
    id: "sm-inh-01", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "multi_turn_followup", risk_type: "normal", scenario_entry_mode: "inherit",
    question: "（场景内）上季度各区域的 GMV",
    flow: [
      { turn: 1, utterance: "订单趋势怎么样" },
      { turn: 2, utterance: "好", clarificationChoice: "scenario:scenario.sales" },
      { turn: 3, utterance: "上季度各区域的 GMV" },
    ],
    expected: { terminal: "query", note: "场景内沿用（inherit）：scope 内指标/维度正常执行" },
    necessary: ["场景状态沿用", "query 执行成功"],
    forbidden: FORBIDDEN_DEFAULT,
  },
  {
    id: "sm-inh-02", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "multi_turn_followup", risk_type: "normal", scenario_entry_mode: "inherit",
    question: "（场景内）各商品品类的销售情况",
    flow: [
      { turn: 1, utterance: "订单趋势怎么样" },
      { turn: 2, utterance: "好", clarificationChoice: "scenario:scenario.sales" },
      { turn: 3, utterance: "各商品品类的销售情况" },
    ],
    expected: { terminal: "clarification", exitCandidate: "scenario:__exit__", note: "scope 外资产 → 越界澄清（候选含退出场景，不静默扩大授权）" },
    necessary: ["越界澄清", "不执行 scope 外查询"],
    forbidden: FORBIDDEN_DEFAULT,
  },
  {
    id: "sm-inh-03", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "multi_turn_followup", risk_type: "normal", scenario_entry_mode: "inherit",
    question: "（场景内）今年总销售额",
    flow: [
      { turn: 1, utterance: "订单趋势怎么样" },
      { turn: 2, utterance: "好", clarificationChoice: "scenario:scenario.sales" },
      { turn: 3, utterance: "今年总销售额" },
    ],
    expected: { terminal: "query", note: "场景内沿用：别名销售额 + 时间解析" },
    necessary: ["场景状态沿用", "query 执行成功"],
    forbidden: FORBIDDEN_DEFAULT,
  },
  {
    id: "sm-inh-04", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "multi_turn_followup", risk_type: "normal", scenario_entry_mode: "inherit",
    question: "（场景内）各区域订单数",
    flow: [
      { turn: 1, utterance: "订单趋势怎么样" },
      { turn: 2, utterance: "好", clarificationChoice: "scenario:scenario.sales" },
      { turn: 3, utterance: "各区域订单数" },
    ],
    expected: { terminal: "query", note: "场景内沿用：metric.order_count + dim.region" },
    necessary: ["场景状态沿用", "query 执行成功"],
    forbidden: FORBIDDEN_DEFAULT,
  },
  {
    id: "sm-inh-05", query_shape: "scenario_mode", analysis_mode: "QUERY",
    turn_type: "multi_turn_followup", risk_type: "normal", scenario_entry_mode: "inherit",
    question: "（场景内）上个月各客户等级的成交金额",
    flow: [
      { turn: 1, utterance: "订单趋势怎么样" },
      { turn: 2, utterance: "好", clarificationChoice: "scenario:scenario.sales" },
      { turn: 3, utterance: "上个月各客户等级的成交金额" },
    ],
    expected: { terminal: "clarification", ambiguity: "客户等级", note: "场景内沿用实测校准：「客户等级」为同词多资产歧义（dim.customer_tier vs property.customer.tier）→ 歧义澄清（场景状态沿用、scope 内候选），不猜测执行" },
    necessary: ["场景状态沿用", "歧义澄清（不猜测执行）"],
    forbidden: FORBIDDEN_DEFAULT,
  },
];

/** 多轮增量补齐（E-2：15 → 30）。T1 建锚，T2 增量维度；降级路径判据 = T1 query + T2 澄清不猜测。 */
const incNew = [
  ["今年各区域成交金额", "再把渠道加上"],
  ["近 7 天订单数", "按地区拆一下"],
  ["上个月各客户等级成交金额", "再加上渠道"],
  ["这个月退款金额", "按地区拆分"],
  ["上季度各渠道订单数", "再加地区维度"],
  ["近 90 天成交金额", "按客户等级看"],
  ["今年客单价", "加地区维度"],
  ["上个月各地区退款金额", "再加客户等级"],
  ["近 30 天各区域订单数", "渠道也加上"],
  ["今年成交金额", "按渠道和地区两个维度看"],
  ["上季度客户数", "再加客户等级"],
  ["这个月各渠道成交金额", "再加地区"],
  ["近 14 天退款金额", "按客户等级拆"],
  ["上个月订单数", "按地区和客户等级拆分"],
  ["今年各客户等级客单价", "渠道也加上"],
].map((turns, i) => ({
  id: `t-turn-incremental-${String(16 + i).padStart(2, "0")}`,
  query_shape: "aggregate",
  analysis_mode: "QUERY",
  turn_type: "multi_turn_incremental",
  risk_type: "normal",
  scenario_entry_mode: "none",
  turns,
  expected: {
    terminal: "query",
    dimensionsAccumulated: true,
    noDimensionLoss: true,
    degradedPathNote: "确定性降级路径判据：T1 query（锚点建立）+ T2 澄清（无指标命中不猜测执行）；维度增量合并完整语义属 LLM 规划职责",
  },
  necessary: ["T1 建立会话锚点", "T2 不编造合并语义（澄清）"],
  forbidden: FORBIDDEN_DEFAULT,
}));

const cases = [
  ...base.cases,
  ...smManual,
  ...smCold,
  ...smInh,
  ...incNew,
];

// ── 分层重算（评测统计口径 v1 枚举）─────────────────────────────────
const tally = (key) => {
  const by = {};
  for (const c of cases) {
    const v = c[key];
    by[v] = (by[v] ?? 0) + 1;
  }
  return by;
};
const sortKeys = (obj) => Object.fromEntries(Object.entries(obj).sort((a, b) => b[1] - a[1]));

const out = {
  version: "v3",
  generatedAt: "2026-11-10",
  manifestId: base.manifestId,
  note:
    "v3（M6/W5 T5.1）：E-1 场景入口题 15 题（manual 5 / cold_start 5 / inherit 5；auto 需 auto_if_confident 场景资产，登记缺口）" +
    " + E-2 多轮增量补至 30 题。场景题判据与 smoke-runner v2 同口径（确定性降级路径，2026-11-10 真服务探针校准）。" +
    "v2 的 241 题零变化；runner 见 scripts/verify/test-runner.mjs。",
  total: cases.length,
  layers: {
    query_shape: sortKeys(tally("query_shape")),
    analysis_mode: sortKeys(tally("analysis_mode")),
    turn_type: sortKeys(tally("turn_type")),
    risk_type: sortKeys(tally("risk_type")),
    scenario_entry_mode: sortKeys(tally("scenario_entry_mode")),
  },
  cases,
};

writeFileSync(OUT, `${JSON.stringify(out, null, 2)}\n`, "utf8");
const fileHash = createHash("sha256").update(readFileSync(OUT)).digest("hex");
console.log(`test集-v3.json 已生成：${out.total} 题`);
console.log("layers:", JSON.stringify(out.layers, null, 1));
console.log("sha256:", fileHash);
