#!/usr/bin/env node
/**
 * test 集 runner（M6/W5 T5.1：主指标评测 + 四维分层统计接线）。
 *
 * 评测口径（与 smoke-runner v2 同一执行路径）：
 * - 固定于确定性降级路径（ontos.agent.enabled=false，M5/T3.1 降级合同）——
 *   LLM 路径评测需稳定模型服务与多次统计运行，登记为 M7 后续项，不阻塞本收敛；
 * - DSL 题按 expected 结构化期望构造（不做自然语言理解，runner 侧确定性补全见 test集-v3.md §6）；
 * - 多轮题判据校准（test 集 v3 升版说明）：T1 query（锚点建立）+ T2 澄清（无指标命中不猜测执行）；
 *   维度增量合并/纠正覆盖的完整语义属 LLM 规划职责；
 * - 跨轮差分题判据：逐轮合法（query/clarification），累计防线由 sensitive-probe 深度覆盖；
 * - skip 不进分母（评测统计口径 v1 §2）。
 *
 * 输出：pass/fail/skip/error + 有效样本 + 分层成功率（Wilson 95%）+ analysis_mode 附加分层
 *       + 失败样本清单（失败看板数据源，五维下钻）。
 *
 * 用法：node scripts/verify/test-runner.mjs [--base-url http://127.0.0.1:8080]
 *       [--set docs/06-verification/evaluation/test集-v3.json]
 */
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const argOf = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};
const SET_PATH = resolve(repo, argOf("--set", "docs/06-verification/evaluation/test集-v3.json"));
const EVIDENCE_PATH = resolve(repo, "docs/06-verification/evaluation/test集-run-latest.json");
const BASE = argOf("--base-url", "http://127.0.0.1:8080");

const sha256 = (buffer) => createHash("sha256").update(buffer).digest("hex");
const set = JSON.parse(readFileSync(SET_PATH, "utf8"));
// M7/W1/T1.1：test 集升 v4（E-5 方案 A，ADR-0018）——runner 接受 v3/v4；
// 更早版本（v1/v2）判据不同，继续拒绝，避免旧题集被误跑。
if (!["v3", "v4"].includes(set.version)) {
  console.error(`test 集版本不受支持：${set.version}（仅 v3/v4）`);
  process.exit(1);
}

const HEADERS = {
  "Content-Type": "application/json",
  "X-Ontos-Tenant": "demo",
  "X-Ontos-User": "test-runner",
  "X-Ontos-Session": `test-${Date.now()}`,
  "X-Ontos-Turn": "1",
};

let MANIFEST_ID = argOf("--manifest-id", null);
if (!MANIFEST_ID) {
  MANIFEST_ID = await fetch(`${BASE}/api/v1/ontology/manifests/current`, { headers: HEADERS })
    .then((response) => response.json())
    .then((body) => body?.data?.manifestId);
  if (!MANIFEST_ID) {
    console.error("无法取得当前生效清单");
    process.exit(1);
  }
}

const runId = `t${Date.now()}`;
let seq = 0;
const nextRequestId = () => `test-${runId}-${++seq}`;

// ── HTTP 通道 ────────────────────────────────────────────────────────
async function dryRun(dsl, userSuffix) {
  const endpoint = dsl.subIntents ? "/api/v1/query/composite/execute" : "/api/v1/query/dry-run";
  const response = await fetch(BASE + endpoint, {
    method: "POST",
    // 每题独立用户：敏感资产累计访问计数按 user+asset 累计（L1/L2 风控），
    // 共享用户会让批量评测自身触发阈值（T51-4），隔离后阈值语义仍由 sensitive-probe 深测
    headers: { ...HEADERS, "X-Ontos-User": `test-${runId}-${userSuffix}`, "X-Request-Id": nextRequestId() },
    body: JSON.stringify({ manifestId: MANIFEST_ID, ...dsl }),
  });
  const payload = await response.json();
  if (response.status >= 500) return { verdict: "error", detail: `HTTP ${response.status}` };
  if (payload.code === 0) return { verdict: "ok", data: payload.data };
  return { verdict: "rejected", errorCode: payload.code, errorMessage: payload.msg ?? "" };
}

async function askTurn(user, session, turn, utterance, clarificationChoice) {
  const body = { utterance };
  if (clarificationChoice) body.clarificationChoice = clarificationChoice;
  const response = await fetch(`${BASE}/api/v1/ask/runs`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Ontos-Tenant": "demo",
      "X-Ontos-User": user,
      "X-Ontos-Session": session,
      "X-Ontos-Turn": String(turn),
    },
    body: JSON.stringify(body),
  });
  const payload = await response.json();
  if (response.status >= 500) return { verdict: "error", detail: `HTTP ${response.status}` };
  if (payload.code !== 0) {
    return { verdict: "rejected", errorCode: payload.code, errorMessage: payload.msg ?? "" };
  }
  return {
    verdict: "ok",
    terminal: payload.data?.terminal ?? null,
    reasonCode: payload.data?.reasonCode ?? null,
    candidates: payload.data?.body?.candidates ?? [],
    rowCount: payload.data?.body?.rowCount ?? null,
  };
}

// ── DSL 构造（expected → 确定性 DSL；runner 侧补全 timeRange.field / limit）─────────
const TIME_FIELD = "dim.order_date";

function buildQueryDsl(expected) {
  const dsl = {
    mode: "QUERY",
    metricId: expected.metricId,
    dimensions: expected.dimensions ?? [],
  };
  if (expected.timeExpressionRaw) dsl.timeExpressionRaw = expected.timeExpressionRaw;
  if (expected.filters) dsl.filters = expected.filters;
  dsl.timeRange = { field: TIME_FIELD };
  return dsl;
}

function buildDsl(input) {
  // M7/W1：预期拒绝题（如 E-5 的 1002000014）只给 terminal/errorCode/metricId，无 mode。
  // 按 period 是否存在做确定性补全（有 period → YOY_MOM，否则 QUERY），仅用于构造被拒 DSL；
  // 不改 expected 判据（仍以 errorCode 精确匹配），也不做语义猜测。
  const expected = !input.mode && input.metricId
    ? { ...input, mode: input.period ? "YOY_MOM" : "QUERY" }
    : input;
  switch (expected.mode) {
    case "QUERY":
      return buildQueryDsl(expected);
    case "YOY_MOM":
      return {
        mode: "YOY_MOM",
        metricId: expected.metricId,
        dimensions: expected.dimensions ?? [],
        analysisParams: { period: expected.period },
        timeExpressionRaw: expected.timeExpressionRaw,
        timeRange: { field: TIME_FIELD },
      };
    case "FUNNEL":
      // 漏斗时间窗只走 analysisParams.cohortWindow（顶层 timeRange 被校验拒绝）
      return {
        mode: "FUNNEL",
        dimensions: expected.dimensions ?? [],
        analysisParams: expected.analysisParams,
      };
    case "RETENTION":
      return {
        mode: "RETENTION",
        dimensions: expected.dimensions ?? [],
        timeExpressionRaw: expected.timeExpressionRaw,
        analysisParams: expected.analysisParams,
      };
    case "ATTRIBUTION":
      // 归因观察窗走顶层 timeExpressionRaw；时间窗由服务端按事件回填（不得提交 timeRange）
      return {
        mode: "ATTRIBUTION",
        metricId: expected.metricId,
        dimensions: expected.dimensions ?? [],
        timeExpressionRaw: expected.timeExpressionRaw,
        analysisParams: expected.analysisParams,
      };
    case "LOOKUP": {
      // 存在性探测题不带 attributes（只探存在）：runner 侧按实体补默认可投影属性
      // （customer → tier，order → amount；均为非敏感投影，冒烟 #6/#8 同款）
      const defaultAttr = expected.entityId === "concept.order"
        ? "property.order.amount" : "property.customer.tier";
      const dsl = {
        mode: "LOOKUP",
        entityId: expected.entityId,
        attributes: (expected.attributes ?? []).length > 0
          ? expected.attributes : [defaultAttr],
        identity: expected.identity,
        limit: 1,
      };
      if (expected.booleanMetricId) dsl.booleanMetricId = expected.booleanMetricId;
      return dsl;
    }
    case "LOOKUP_BATCH": {
      // 单体存在性探测（t-existence-06）以 identity 单对象表达：归一化为单元素批量
      const identities = expected.identities
        ?? [expected.identity?.value].filter(Boolean);
      if (identities.length === 0) return null;
      const defaultAttr = expected.entityId === "concept.order"
        ? "property.order.amount" : "property.customer.tier";
      return {
        mode: "LOOKUP_BATCH",
        entityId: expected.entityId,
        identityPropertyId: expected.identityPropertyId
          ?? expected.identity?.propertyId,
        attributes: (expected.attributes ?? []).length > 0
          ? expected.attributes : [defaultAttr],
        identities,
        limit: identities.length,
      };
    }
    default:
      return null;
  }
}

/** 无依赖复合题 11–25（expected.subIntents 为子查询计数）：按题面语义的确定性映射（audit-friendly）。 */
const COMPOSITE_COUNT_DSL = {
  "t-composite-11": [{ m: "metric.gmv", d: ["dim.region"] }, { m: "metric.order_count", d: ["dim.channel"] }],
  "t-composite-12": [{ m: "metric.distinct_customers", d: ["dim.customer_tier"] }, { m: "metric.avg_order_value", d: ["dim.region"] }],
  "t-composite-13": [{ m: "metric.order_count", d: [], f: [{ fieldId: "dim.customer_tier", operator: "EQ", values: ["gold"] }] }, { m: "metric.gmv", d: [], f: [{ fieldId: "dim.channel", operator: "EQ", values: ["线上"] }] }],
  "t-composite-14": [{ m: "metric.refund_amount", d: [], t: "上个月" }, { m: "metric.gmv", d: [], t: "今年" }],
  "t-composite-15": [{ m: "metric.gmv", d: ["dim.channel"] }, { m: "metric.order_count", d: ["dim.customer_tier"] }],
  "t-composite-16": [{ m: "metric.order_count", d: [], f: [{ fieldId: "dim.region", operator: "EQ", values: ["华东"] }] }, { m: "metric.gmv", d: [], f: [{ fieldId: "dim.region", operator: "EQ", values: ["华南"] }] }],
  "t-composite-17": [{ m: "metric.avg_order_value", d: ["dim.customer_tier"] }, { m: "metric.distinct_customers", d: ["dim.channel"] }],
  "t-composite-18": [{ m: "metric.gmv", d: [], t: "这个月" }, { m: "metric.order_count", d: [], t: "上个月" }],
  "t-composite-19": [{ m: "metric.order_count", d: [], t: "近 30 天" }, { m: "metric.distinct_customers", d: [], t: "近 60 天" }],
  "t-composite-20": [{ m: "metric.gmv", d: [], t: "上季度" }, { m: "metric.order_count", d: [], t: "上季度" }],
  "t-composite-21": [{ m: "metric.refund_amount", d: ["dim.region"] }, { m: "metric.refund_amount", d: ["dim.customer_tier"] }],
  "t-composite-22": [{ m: "metric.avg_order_value", d: ["dim.channel"] }, { m: "metric.order_count", d: ["dim.customer_tier"] }],
  "t-composite-23": [{ m: "metric.distinct_customers", d: ["dim.region"], t: "今年" }, { m: "metric.distinct_customers", d: ["dim.channel"], t: "今年" }],
  "t-composite-24": [{ m: "metric.gmv", d: ["dim.region"], t: "上个月" }, { m: "metric.order_count", d: ["dim.channel"], t: "上个月" }],
  "t-composite-25": [{ m: "metric.gmv", d: [], t: "近 14 天" }, { m: "metric.order_count", d: [], t: "近 14 天" }],
};

/** 依赖复合题的确定性 DSL（映射注记：dep-02「月度趋势」以同维聚合替代——
 *  时间桶分组归 LLM derive 翻译（决策 6），本 runner 只验依赖注入链路）。 */
const COMPOSITE_DEP_DSL = {
  "t-composite-dep-01": [
    { subIntentId: "s1", metricId: "metric.gmv", mode: "QUERY", dimensions: ["dim.region"], timeExpressionRaw: "上个月", timeRange: { field: TIME_FIELD } },
    { subIntentId: "s2", metricId: "metric.gmv", mode: "QUERY", dimensions: ["dim.channel"], timeExpressionRaw: "上个月", timeRange: { field: TIME_FIELD },
      filters: [{ fieldId: "dim.region", operator: "EQ", values: [] }], dependsOn: ["s1"],
      resultRef: { fromSubIntentId: "s1", selector: { type: "ordered_top", row: 1, column: "dim.region" }, inject: { type: "filter_value", filterIndex: 0 } } },
  ],
  "t-composite-dep-02": [
    { subIntentId: "s1", metricId: "metric.order_count", mode: "QUERY", dimensions: ["dim.region"], timeExpressionRaw: "今年", timeRange: { field: TIME_FIELD } },
    { subIntentId: "s2", metricId: "metric.order_count", mode: "QUERY", dimensions: ["dim.region"], timeExpressionRaw: "今年", timeRange: { field: TIME_FIELD },
      filters: [{ fieldId: "dim.region", operator: "EQ", values: [] }], dependsOn: ["s1"],
      resultRef: { fromSubIntentId: "s1", selector: { type: "ordered_top", row: 1, column: "dim.region" }, inject: { type: "filter_value", filterIndex: 0 } } },
  ],
  "t-composite-dep-03": [
    { subIntentId: "s1", metricId: "metric.avg_order_value", mode: "QUERY", dimensions: ["dim.channel"], timeExpressionRaw: "近 30 天", timeRange: { field: TIME_FIELD } },
    { subIntentId: "s2", metricId: "metric.refund_amount", mode: "QUERY", dimensions: [], timeExpressionRaw: "近 30 天", timeRange: { field: TIME_FIELD },
      filters: [{ fieldId: "dim.channel", operator: "EQ", values: [] }], dependsOn: ["s1"],
      resultRef: { fromSubIntentId: "s1", selector: { type: "ordered_top", row: 1, column: "dim.channel" }, inject: { type: "filter_value", filterIndex: 0 } } },
  ],
  "t-composite-dep-04": [
    { subIntentId: "s1", metricId: "metric.gmv", mode: "QUERY", dimensions: ["dim.customer_tier"], timeExpressionRaw: "上季度", timeRange: { field: TIME_FIELD } },
    { subIntentId: "s2", metricId: "metric.order_count", mode: "QUERY", dimensions: [], timeExpressionRaw: "上季度", timeRange: { field: TIME_FIELD },
      filters: [{ fieldId: "dim.customer_tier", operator: "EQ", values: [] }], dependsOn: ["s1"],
      resultRef: { fromSubIntentId: "s1", selector: { type: "ordered_top", row: 1, column: "dim.customer_tier" }, inject: { type: "filter_value", filterIndex: 0 } } },
  ],
  "t-composite-dep-05": [
    { subIntentId: "s1", metricId: "metric.gmv", mode: "QUERY", dimensions: ["dim.region"], timeExpressionRaw: "今年", timeRange: { field: TIME_FIELD } },
    { subIntentId: "s2", metricId: "metric.gmv", mode: "QUERY", dimensions: ["dim.channel"], timeExpressionRaw: "今年", timeRange: { field: TIME_FIELD },
      filters: [{ fieldId: "dim.region", operator: "EQ", values: [] }], dependsOn: ["s1"],
      resultRef: { fromSubIntentId: "s1", selector: { type: "ordered_top", row: 1, column: "dim.region" }, inject: { type: "filter_value", filterIndex: 0 } } },
  ],
};

// ── 逐题执行 ─────────────────────────────────────────────────────────
const results = [];

function record(c, status, extra = {}) {
  results.push({
    id: c.id,
    status,
    query_shape: c.query_shape,
    analysis_mode: c.analysis_mode,
    turn_type: c.turn_type,
    risk_type: c.risk_type,
    scenario_entry_mode: c.scenario_entry_mode,
    is_composite: c.analysis_mode === "COMPOSITE",
    ...extra,
  });
}

/** 终态判据：expected.terminal 支持「A 或 B」多值（跨域/差分族的既定口径）。 */
function terminalAccepted(expectedTerminal, terminal) {
  const accepted = expectedTerminal.split("或").map((s) => s.trim());
  return accepted.includes(terminal);
}

for (const c of set.cases) {
  // —— skip（不进分母）——
  if (c.skipReason) {
    record(c, "skip", { reason: c.skipReason });
    continue;
  }

  // —— 场景 manual 题（DSL 直传，case.dsl 显式给出；runner 侧补全 timeRange.field）——
  if (c.id.startsWith("sm-manual-")) {
    const dsl = { ...c.dsl };
    if (dsl.timeExpressionRaw && !dsl.timeRange) {
      dsl.timeRange = { field: TIME_FIELD };
    }
    const outcome = await dryRun(dsl, c.id);
    const pass =
      outcome.verdict === "ok"
        ? c.expected.terminal === "query"
        : outcome.verdict === "rejected"
          ? c.expected.errorCode != null && outcome.errorCode === c.expected.errorCode
          : false;
    record(c, pass ? "pass" : "fail", {
      verdict: outcome.verdict,
      errorCode: outcome.errorCode ?? null,
      reason: outcome.errorMessage || outcome.detail || null,
    });
    continue;
  }

  // —— 场景 cold_start / inherit 题（ask 流，case.flow 逐轮）——
  if (c.id.startsWith("sm-cold-") || c.id.startsWith("sm-inh-")) {
    const user = `test-${runId}-${c.id}`;
    const session = `sess-${c.id}`;
    let pass = true;
    let last = null;
    for (const step of c.flow) {
      last = await askTurn(user, session, step.turn, step.utterance, step.clarificationChoice);
      if (last.verdict === "error") { pass = false; break; }
    }
    // 判定最后一轮终态；cold-04 额外断言退出轮为 discovery（不进场景）+ 第三轮 query
    if (pass && c.id === "sm-cold-04") {
      const exitTurn = await last; // 最后一轮（第三轮）
      pass = exitTurn.verdict === "ok" && exitTurn.terminal === "query";
    } else if (pass) {
      pass = last.verdict === "ok" && terminalAccepted(c.expected.terminal, last.terminal);
    }
    // 结构断言：cold-01 必须含场景推荐候选；inh-02 必须含退出候选
    if (pass && c.expected.scenarioCandidate) {
      pass = (last.candidates ?? []).some((cand) => cand.assetId === c.expected.scenarioCandidate);
    }
    if (pass && c.expected.exitCandidate) {
      pass = (last.candidates ?? []).some((cand) => cand.assetId === c.expected.exitCandidate);
    }
    record(c, pass ? "pass" : "fail", {
      verdict: last?.verdict,
      terminal: last?.terminal ?? null,
      reasonCode: last?.reasonCode ?? null,
      reason: last?.errorMessage || last?.detail || null,
    });
    continue;
  }

  // —— 多轮题（followup / incremental / correction；降级路径校准判据）——
  // 判据（test 集 v3 升版说明）：逐轮终态合法（query/clarification/discovery）且无 error
  // ——会话不串号、不编造执行；T1 是否 query 取决于资产别名覆盖（退款金额/客单价等
  // 未注册别名 → 澄清是正确的降级行为），维度合并/纠正覆盖的完整语义属 LLM 规划职责。
  if (Array.isArray(c.turns)) {
    const user = `test-${runId}-${c.id}`;
    const session = `sess-${c.id}`;
    let pass = true;
    const turnVerdicts = [];
    for (let i = 0; i < c.turns.length; i += 1) {
      const turn = await askTurn(user, session, i + 1, c.turns[i]);
      turnVerdicts.push(turn.terminal ?? turn.errorCode ?? turn.verdict);
      if (turn.verdict !== "ok"
        || !["query", "clarification", "discovery"].includes(turn.terminal)) {
        pass = false;
        break;
      }
    }
    record(c, pass ? "pass" : "fail", { turnVerdicts });
    continue;
  }

  // M6 全量深度审核 D-10：合并跨轮差分死分支（原 t-diff 独立分支被上面的 turns 捕获，永不执行）。
  // 差分族的累计防线深度由 sensitive-probe（live 基线 32 项）覆盖，此处判据同为逐轮合法终态。
  if (c.id.startsWith("t-diff-") && !Array.isArray(c.turns)) {
    record(c, "error", { reason: "跨轮差分题缺少 turns（题面结构非法，不允许静默判过）" });
    continue;
  }

  // —— 注入族（ask；注入启发确定性拒答）——
  if (c.id.startsWith("t-injection-")) {
    const turn = await askTurn(`test-${runId}-${c.id}`, `sess-${c.id}`, 1, c.question);
    const pass = turn.verdict === "ok" && turn.terminal === "out_of_capability";
    record(c, pass ? "pass" : "fail", {
      verdict: turn.verdict, terminal: turn.terminal ?? null,
      reasonCode: turn.reasonCode ?? null,
    });
    continue;
  }

  // —— 跨域（首发单域 → 拒绝或澄清）——
  if (c.id.startsWith("t-cross-domain-")) {
    const turn = await askTurn(`test-${runId}-${c.id}`, `sess-${c.id}`, 1, c.question);
    const pass = turn.verdict === "ok" && terminalAccepted(c.expected.terminal, turn.terminal);
    record(c, pass ? "pass" : "fail", {
      verdict: turn.verdict, terminal: turn.terminal ?? null,
      reasonCode: turn.reasonCode ?? null,
    });
    continue;
  }

  // —— discovery（ask → discovery 三键载荷；模糊命中落澄清候选列举为同级引导终态）——
  if (c.id.startsWith("t-discovery-")) {
    const turn = await askTurn(`test-${runId}-${c.id}`, `sess-${c.id}`, 1, c.question);
    const pass = turn.verdict === "ok"
      && ["discovery", "clarification"].includes(turn.terminal);
    record(c, pass ? "pass" : "fail", {
      verdict: turn.verdict, terminal: turn.terminal ?? null,
      reasonCode: turn.reasonCode ?? null,
    });
    continue;
  }

  // —— 依赖复合（显式 DSL 映射）——
  if (COMPOSITE_DEP_DSL[c.id]) {
    const outcome = await dryRun({ mode: "QUERY", subIntents: COMPOSITE_DEP_DSL[c.id] }, c.id);
    const pass = outcome.verdict === "ok";
    record(c, pass ? "pass" : "fail", {
      verdict: outcome.verdict, errorCode: outcome.errorCode ?? null,
      reason: outcome.errorMessage || outcome.detail || null,
    });
    continue;
  }

  // —— 无依赖复合（expected.subIntents 结构化 → composite 端点；计数型 → 显式映射）——
  if (Array.isArray(c.expected?.subIntents)) {
    const subIntents = c.expected.subIntents.map((sub, i) => ({
      subIntentId: `s${i + 1}`,
      metricId: sub.metricId,
      mode: "QUERY",
      dimensions: sub.dimensions ?? [],
      timeExpressionRaw: sub.timeExpressionRaw,
      timeRange: { field: TIME_FIELD },
    }));
    const outcome = await dryRun({ mode: "QUERY", subIntents }, c.id);
    const pass = outcome.verdict === "ok";
    record(c, pass ? "pass" : "fail", {
      verdict: outcome.verdict, errorCode: outcome.errorCode ?? null,
      reason: outcome.errorMessage || outcome.detail || null,
    });
    continue;
  }
  if (COMPOSITE_COUNT_DSL[c.id]) {
    const subIntents = COMPOSITE_COUNT_DSL[c.id].map((sub, i) => ({
      subIntentId: `s${i + 1}`,
      metricId: sub.m,
      mode: "QUERY",
      dimensions: sub.d,
      timeExpressionRaw: sub.t,
      filters: sub.f,
      timeRange: { field: TIME_FIELD },
    }));
    const outcome = await dryRun({ mode: "QUERY", subIntents }, c.id);
    const pass = outcome.verdict === "ok";
    record(c, pass ? "pass" : "fail", {
      verdict: outcome.verdict, errorCode: outcome.errorCode ?? null,
      reason: outcome.errorMessage || outcome.detail || null,
    });
    continue;
  }

  // —— 单轮 DSL 题（含 E-5 负例哨兵：expected.errorCode 精确匹配）——
  const dsl = buildDsl(c.expected ?? {});
  if (!dsl) {
    record(c, "error", { reason: `无法构造 DSL（expected 缺 mode）: ${JSON.stringify(c.expected).slice(0, 80)}` });
    continue;
  }
  const outcome = await dryRun(dsl, c.id);
  let pass;
  if (c.expected.errorCode != null) {
    pass = outcome.verdict === "rejected" && outcome.errorCode === c.expected.errorCode;
  } else if (c.expected.terminal === "query") {
    pass = outcome.verdict === "ok";
  } else {
    // M6 全量深度审核 D-11：移除恒假子表达式（原 `ok && false` 永假、掩盖判据意图）
    pass = outcome.verdict === "rejected";
  }
  record(c, pass ? "pass" : "fail", {
    verdict: outcome.verdict, errorCode: outcome.errorCode ?? null,
    reason: outcome.errorMessage || outcome.detail || null,
  });
}

// ── 分层统计 + Wilson 95%（评测统计口径 v1 §3/§4）─────────────────────
const wilson = (pass, n) => {
  if (n === 0) return null;
  const z = 1.96;
  const p = pass / n;
  const denom = 1 + (z * z) / n;
  const center = (p + (z * z) / (2 * n)) / denom;
  const half = (z * Math.sqrt((p * (1 - p)) / n + (z * z) / (4 * n * n))) / denom;
  return [Number((center - half).toFixed(4)), Number((center + half).toFixed(4))];
};
// M6 全量深度审核 D-9：分层 n = 有效样本（pass+fail），skip/error 单列且不进 Wilson 分母
// （评测统计口径 v1 §2「skip 不算通过、不进分母」/ §4「n = 有效样本数」）。
const layer = (key) => {
  const by = {};
  for (const row of results) {
    const v = row[key];
    by[v] ??= { n: 0, pass: 0, skip: 0, error: 0 };
    if (row.status === "skip") { by[v].skip += 1; continue; }
    if (row.status === "error") { by[v].error += 1; continue; }
    by[v].n += 1;
    if (row.status === "pass") by[v].pass += 1;
  }
  return Object.fromEntries(Object.entries(by).map(([k, v]) => [
    k, { ...v, wilson95: wilson(v.pass, v.n) },
  ]));
};
const passCount = results.filter((r) => r.status === "pass").length;
const failCount = results.filter((r) => r.status === "fail").length;
const valid = passCount + failCount;

// ── 失败样本落库（失败看板数据流：runner → 管理面 API → 五维下钻，T5.1 交付物 ④）──
const failureRows = results
  .filter((r) => r.status === "fail")
  .map((r) => ({
    questionId: r.id,
    scenarioEntryMode: r.scenario_entry_mode,
    queryShape: r.query_shape,
    turnType: r.turn_type,
    isComposite: r.is_composite,
    riskType: r.risk_type,
    // M6 全量深度审核 D-12：期望终态取题集 expected（原误填实测 r.terminal）
    expectedTerminal: set.cases.find((x) => x.id === r.id)?.expected?.terminal ?? null,
    actualTerminal: r.terminal ?? (r.errorCode ? `code:${r.errorCode}` : (r.verdict ?? null)),
    errorCode: r.errorCode ?? null,
    reason: r.reason ?? null,
  }));
let pushResult = { pushed: 0, skipped: true, note: "无失败样本（看板空态）" };
if (failureRows.length > 0) {
  try {
    const pushResponse = await fetch(`${BASE}/api/v1/evaluation/failure-samples`, {
      method: "POST",
      headers: { ...HEADERS },
      body: JSON.stringify({
        setId: SET_PATH.split("/").pop().replace(/\.json$/, "").replace(/test集-/, "test-"),
        runId,
        samples: failureRows,
      }),
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
  runnerVersion: "test-runner-v1",
  agentMode: "ontos.agent.enabled=false（确定性降级路径，与 smoke-runner v2 同口径）",
  setFile: SET_PATH,
  setHashes: {
    [SET_PATH.split("/").pop()]: `sha256:${sha256(readFileSync(SET_PATH))}`,
    total: set.total,
  },
  summary: {
    total: set.total,
    pass: passCount,
    fail: failCount,
    skip: results.filter((r) => r.status === "skip").length,
    error: results.filter((r) => r.status === "error").length,
    validSamples: valid,
    successRate: valid === 0 ? null : Number((passCount / valid).toFixed(4)),
    wilson95: wilson(passCount, valid),
  },
  layers: {
    scenario_entry_mode: layer("scenario_entry_mode"),
    query_shape: layer("query_shape"),
    turn_type: layer("turn_type"),
    risk_type: layer("risk_type"),
    analysis_mode: layer("analysis_mode"),
  },
  failures: results.filter((r) => r.status === "fail" || r.status === "error"),
  dashboardPush: pushResult,
  results,
};
writeFileSync(EVIDENCE_PATH, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");

for (const row of results) {
  if (row.status === "pass") continue;
  const badge = row.status === "skip" ? "SKIP" : "FAIL";
  console.log(`  ${badge} ${row.id} [${row.query_shape}/${row.scenario_entry_mode}] ${row.reason ?? row.errorCode ?? JSON.stringify(row.turnVerdicts ?? row.terminal)}`);
}
console.log(
  `test: ${passCount} pass / ${failCount} fail / ${results.filter((r) => r.status === "skip").length} skip` +
    ` / ${results.filter((r) => r.status === "error").length} error（成功率 ${evidence.summary.successRate}，Wilson95 ${evidence.summary.wilson95}）`,
);
process.exit(failCount > 0 || results.some((r) => r.status === "error") ? 1 : 0);
