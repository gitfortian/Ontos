#!/usr/bin/env node
/**
 * M6 Golden 增量（M6 规格 §5 / E1：逐字比对，复用 golden-sql 与 m3-golden 的判据口径）。
 *
 * 覆盖（T1.3 留存段）：
 *   - 正例 5：周/月周期、带主体维度、上季度观察窗、cohort/return 事件对不同；
 *   - 负例 5：periods 越界 / 未知事件 / 客户端提交 timeRange / 非主体表维度 / filters。
 * 固化内容：SQL 字符串 + 负例错误码；绑定值含观察锚点（随日期变化）只作参考、不参与逐字比对，
 * 可复现性用「同次运行两次编译比对」。check 时把 DSL 的 manifestId 对齐当前生效清单。
 *
 * 用法：
 *   node scripts/verify/m6-golden.mjs --mode=backfill [--section=retention]
 *   node scripts/verify/m6-golden.mjs --mode=check    [--section=retention]
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const BASE = process.env.ONTOS_BASE_URL ?? "http://127.0.0.1:8080";
const GOLD = resolve(repo, "docs/06-verification/evaluation/M6-Golden-v1.json");
const MODE = process.argv.includes("--mode=check") ? "check" : "backfill";

const argOf = (name, fallback) => {
  const index = process.argv.indexOf(name);
  return index > -1 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
};

const HEADERS = {
  "Content-Type": "application/json",
  "X-Ontos-Tenant": "demo",
  "X-Ontos-User": "m6-golden",
  "X-Ontos-Session": "m6-golden",
  "X-Ontos-Turn": "1",
};

let MANIFEST_ID = argOf("--manifest-id", null);
if (!MANIFEST_ID) {
  MANIFEST_ID = await fetch(`${BASE}/api/v1/ontology/manifests/current`, { headers: HEADERS })
    .then((response) => response.json())
    .then((body) => body?.data?.manifestId);
  if (!MANIFEST_ID) {
    console.error("无法取得当前生效清单，请用 --manifest-id 显式指定");
    process.exit(1);
  }
  console.log(`使用当前生效清单: ${MANIFEST_ID}`);
}

const SUBJECT = "property.customer.customer_id";
/** 首发唯一「映射主体表」的可分组维度（清单 v11：dim.customer_tier → customer.tier）。 */
const SUBJECT_DIMENSION = "dim.customer_tier";

/**
 * T1.3 留存案例集。负例的 expectErrorCode 来自 ErrorCodeConstants：
 * DSL_INVALID 1002000000 / RETENTION_EVENT_NOT_FOUND 1002000035 /
 * RETENTION_DIMENSION_UNSUPPORTED 1002000036。
 */
const CASES = [
  {
    section: "retention",
    id: "m6-ret-week-basic",
    title: "周留存（4 期，无维度）",
    kind: "sql",
    dsl: {
      mode: "RETENTION",
      timeExpressionRaw: "近 90 天",
      analysisParams: {
        cohortEvent: "ORDER_PLACED",
        returnEvent: "ORDER_PLACED",
        periodUnit: "WEEK",
        periods: 4,
        subjectPropertyId: SUBJECT,
      },
      limit: 100,
    },
  },
  {
    section: "retention",
    id: "m6-ret-week-tier",
    title: "周留存（8 期，主体维度）",
    kind: "sql",
    dsl: {
      mode: "RETENTION",
      dimensions: [SUBJECT_DIMENSION],
      timeExpressionRaw: "近 90 天",
      analysisParams: {
        cohortEvent: "ORDER_PLACED",
        returnEvent: "ORDER_PLACED",
        periodUnit: "WEEK",
        periods: 8,
        subjectPropertyId: SUBJECT,
      },
      limit: 200,
    },
  },
  {
    section: "retention",
    id: "m6-ret-month-basic",
    title: "月留存（6 期，「今年」观察窗）",
    kind: "sql",
    dsl: {
      mode: "RETENTION",
      timeExpressionRaw: "今年",
      analysisParams: {
        cohortEvent: "ORDER_PLACED",
        returnEvent: "ORDER_PLACED",
        periodUnit: "MONTH",
        periods: 6,
        subjectPropertyId: SUBJECT,
      },
    },
  },
  {
    section: "retention",
    id: "m6-ret-last-quarter",
    title: "月留存（上季度观察窗，periodUnit 与解析粒度独立）",
    kind: "sql",
    dsl: {
      mode: "RETENTION",
      timeExpressionRaw: "上季度",
      analysisParams: {
        cohortEvent: "ORDER_PLACED",
        returnEvent: "ORDER_PLACED",
        periodUnit: "MONTH",
        periods: 3,
        subjectPropertyId: SUBJECT,
      },
    },
  },
  {
    section: "retention",
    id: "m6-ret-event-pair",
    title: "事件对留存（下单 → 退款）",
    kind: "sql",
    dsl: {
      mode: "RETENTION",
      timeExpressionRaw: "近 60 天",
      analysisParams: {
        cohortEvent: "ORDER_PLACED",
        returnEvent: "REFUND",
        periodUnit: "WEEK",
        periods: 4,
        subjectPropertyId: SUBJECT,
      },
    },
  },
  {
    section: "retention",
    id: "m6-ret-neg-periods",
    title: "负例：periods 越界",
    kind: "negative",
    expectErrorCode: 1002000000,
    dsl: {
      mode: "RETENTION",
      timeExpressionRaw: "近 90 天",
      analysisParams: {
        cohortEvent: "ORDER_PLACED",
        returnEvent: "ORDER_PLACED",
        periodUnit: "WEEK",
        periods: 13,
        subjectPropertyId: SUBJECT,
      },
    },
  },
  {
    section: "retention",
    id: "m6-ret-neg-event",
    title: "负例：未发布事件类型",
    kind: "negative",
    expectErrorCode: 1002000035,
    dsl: {
      mode: "RETENTION",
      timeExpressionRaw: "近 90 天",
      analysisParams: {
        cohortEvent: "ORDER_PLACED",
        returnEvent: "NOT_PUBLISHED",
        periodUnit: "WEEK",
        periods: 4,
        subjectPropertyId: SUBJECT,
      },
    },
  },
  {
    section: "retention",
    id: "m6-ret-neg-client-timerange",
    title: "负例：客户端提交 timeRange（服务端回填通道不可占用）",
    kind: "negative",
    expectErrorCode: 1002000000,
    dsl: {
      mode: "RETENTION",
      timeExpressionRaw: "近 90 天",
      timeRange: { field: "dim.order_date", from: "2026-01-01T00:00:00Z", to: "2026-06-01T00:00:00Z" },
      analysisParams: {
        cohortEvent: "ORDER_PLACED",
        returnEvent: "ORDER_PLACED",
        periodUnit: "WEEK",
        periods: 4,
        subjectPropertyId: SUBJECT,
      },
    },
  },
  {
    section: "retention",
    id: "m6-ret-neg-event-dimension",
    title: "负例：事件级维度（非主体表）",
    kind: "negative",
    expectErrorCode: 1002000036,
    dsl: {
      mode: "RETENTION",
      dimensions: ["dim.channel"],
      timeExpressionRaw: "近 90 天",
      analysisParams: {
        cohortEvent: "ORDER_PLACED",
        returnEvent: "ORDER_PLACED",
        periodUnit: "WEEK",
        periods: 4,
        subjectPropertyId: SUBJECT,
      },
    },
  },
  {
    section: "retention",
    id: "m6-ret-neg-filters",
    title: "负例：filters 未定档（拒绝而非静默忽略）",
    kind: "negative",
    expectErrorCode: 1002000000,
    dsl: {
      mode: "RETENTION",
      timeExpressionRaw: "近 90 天",
      filters: [{ fieldId: "dim.customer_tier", operator: "EQ", values: ["gold"] }],
      analysisParams: {
        cohortEvent: "ORDER_PLACED",
        returnEvent: "ORDER_PLACED",
        periodUnit: "WEEK",
        periods: 4,
        subjectPropertyId: SUBJECT,
      },
    },
  },
];

/**
 * T1.4 归因案例集。负例错误码：ATTRIBUTION_EVENT_NOT_FOUND 1002000038 /
 * ATTRIBUTION_DIMENSION_UNSUPPORTED 1002000039 / ATTRIBUTION_MODEL_NOT_IMPLEMENTED 1002000040。
 */
const ATTRIBUTION_CASES = [
  {
    section: "attribution",
    id: "m6-att-last-touch",
    title: "末次触点归因（gmv，30d 窗口，渠道维度）",
    kind: "sql",
    dsl: {
      mode: "ATTRIBUTION",
      metricId: "metric.gmv",
      dimensions: ["dim.channel"],
      timeExpressionRaw: "近 30 天",
      analysisParams: {
        conversionEvent: "ORDER_PLACED",
        touchEvent: "CHANNEL_TOUCH",
        attributionWindow: "30d",
        model: "last_touch",
        subjectPropertyId: SUBJECT,
      },
      limit: 100,
    },
  },
  {
    section: "attribution",
    id: "m6-att-order-count",
    title: "末次触点归因（order_count，7d 窗口）",
    kind: "sql",
    dsl: {
      mode: "ATTRIBUTION",
      metricId: "metric.order_count",
      dimensions: ["dim.channel"],
      timeExpressionRaw: "近 30 天",
      analysisParams: {
        conversionEvent: "ORDER_PLACED",
        touchEvent: "CHANNEL_TOUCH",
        attributionWindow: "7d",
        model: "last_touch",
        subjectPropertyId: SUBJECT,
      },
    },
  },
  {
    section: "attribution",
    id: "m6-att-no-channel-dimension",
    title: "无渠道维度（不产出 UNATTRIBUTED 桶）",
    kind: "sql",
    dsl: {
      mode: "ATTRIBUTION",
      metricId: "metric.gmv",
      timeExpressionRaw: "近 30 天",
      analysisParams: {
        conversionEvent: "ORDER_PLACED",
        touchEvent: "CHANNEL_TOUCH",
        attributionWindow: "30d",
        model: "last_touch",
        subjectPropertyId: SUBJECT,
      },
    },
  },
  {
    section: "attribution",
    id: "m6-att-14d-window",
    title: "14d 回溯窗口（枚举边界）",
    kind: "sql",
    dsl: {
      mode: "ATTRIBUTION",
      metricId: "metric.gmv",
      dimensions: ["dim.channel"],
      timeExpressionRaw: "近 30 天",
      analysisParams: {
        conversionEvent: "ORDER_PLACED",
        touchEvent: "CHANNEL_TOUCH",
        attributionWindow: "14d",
        model: "last_touch",
        subjectPropertyId: SUBJECT,
      },
    },
  },
  {
    section: "attribution",
    id: "m6-att-neg-unknown-event",
    title: "负例：未发布触点事件",
    kind: "negative",
    expectErrorCode: 1002000038,
    dsl: {
      mode: "ATTRIBUTION",
      metricId: "metric.gmv",
      dimensions: ["dim.channel"],
      timeExpressionRaw: "近 30 天",
      analysisParams: {
        conversionEvent: "ORDER_PLACED",
        touchEvent: "NOT_PUBLISHED",
        attributionWindow: "30d",
        model: "last_touch",
        subjectPropertyId: SUBJECT,
      },
    },
  },
  {
    section: "attribution",
    id: "m6-att-neg-unimplemented-model",
    title: "负例：声明未实现的归因模型（linear）",
    kind: "negative",
    expectErrorCode: 1002000040,
    dsl: {
      mode: "ATTRIBUTION",
      metricId: "metric.gmv",
      dimensions: ["dim.channel"],
      timeExpressionRaw: "近 30 天",
      analysisParams: {
        conversionEvent: "ORDER_PLACED",
        touchEvent: "CHANNEL_TOUCH",
        attributionWindow: "30d",
        model: "linear",
        subjectPropertyId: SUBJECT,
      },
    },
  },
  {
    section: "attribution",
    id: "m6-att-neg-dimension",
    title: "负例：非指标同表维度（客户等级）",
    kind: "negative",
    expectErrorCode: 1002000039,
    dsl: {
      mode: "ATTRIBUTION",
      metricId: "metric.gmv",
      dimensions: ["dim.customer_tier"],
      timeExpressionRaw: "近 30 天",
      analysisParams: {
        conversionEvent: "ORDER_PLACED",
        touchEvent: "CHANNEL_TOUCH",
        attributionWindow: "30d",
        model: "last_touch",
        subjectPropertyId: SUBJECT,
      },
    },
  },
  {
    section: "attribution",
    id: "m6-att-neg-client-timerange",
    title: "负例：客户端提交 timeRange",
    kind: "negative",
    expectErrorCode: 1002000000,
    dsl: {
      mode: "ATTRIBUTION",
      metricId: "metric.gmv",
      dimensions: ["dim.channel"],
      timeExpressionRaw: "近 30 天",
      timeRange: { field: "dim.order_date", from: "2026-01-01T00:00:00Z", to: "2026-06-01T00:00:00Z" },
      analysisParams: {
        conversionEvent: "ORDER_PLACED",
        touchEvent: "CHANNEL_TOUCH",
        attributionWindow: "30d",
        model: "last_touch",
        subjectPropertyId: SUBJECT,
      },
    },
  },
];

/** T1.5 funnel 顶层 filters（全局过滤）案例集。 */
const FUNNEL_CASES = [
  {
    section: "funnel",
    id: "m6-funnel-global-filter-eq",
    title: "漏斗全局过滤（主体维度 EQ）",
    kind: "sql",
    dsl: {
      mode: "FUNNEL",
      dimensions: ["dim.customer_tier"],
      filters: [{ fieldId: "dim.customer_tier", operator: "EQ", values: ["gold"] }],
      analysisParams: {
        steps: ["ORDER_PLACED", "REFUND"],
        cohortWindow: { timeExpressionRaw: "近 90 天" },
        stepWindow: "7d",
        subjectPropertyId: SUBJECT,
        deduplication: "first_occurrence",
        conversionDenominator: "previous_step",
      },
      limit: 100,
    },
  },
  {
    section: "funnel",
    id: "m6-funnel-global-filter-in",
    title: "漏斗全局过滤（多值 IN，无分组维度）",
    kind: "sql",
    dsl: {
      mode: "FUNNEL",
      filters: [{ fieldId: "dim.customer_tier", operator: "IN", values: ["gold", "normal"] }],
      analysisParams: {
        steps: ["ORDER_PLACED", "REFUND"],
        cohortWindow: { timeExpressionRaw: "近 60 天" },
        stepWindow: "14d",
        subjectPropertyId: SUBJECT,
        deduplication: "first_occurrence",
        conversionDenominator: "previous_step",
      },
    },
  },
  {
    section: "funnel",
    id: "m6-funnel-neg-filter-non-subject",
    title: "负例：过滤字段非主体表（渠道维度）",
    kind: "negative",
    expectErrorCode: 1002000023,
    dsl: {
      mode: "FUNNEL",
      filters: [{ fieldId: "dim.channel", operator: "EQ", values: ["线上"] }],
      analysisParams: {
        steps: ["ORDER_PLACED", "REFUND"],
        cohortWindow: { timeExpressionRaw: "近 60 天" },
        stepWindow: "7d",
        subjectPropertyId: SUBJECT,
        deduplication: "first_occurrence",
        conversionDenominator: "previous_step",
      },
    },
  },
  {
    section: "funnel",
    id: "m6-funnel-neg-filter-unpublished",
    title: "负例：过滤字段未发布维度",
    kind: "negative",
    expectErrorCode: 1001000003,
    dsl: {
      mode: "FUNNEL",
      filters: [{ fieldId: "dim.not_published", operator: "EQ", values: ["x"] }],
      analysisParams: {
        steps: ["ORDER_PLACED", "REFUND"],
        cohortWindow: { timeExpressionRaw: "近 60 天" },
        stepWindow: "7d",
        subjectPropertyId: SUBJECT,
        deduplication: "first_occurrence",
        conversionDenominator: "previous_step",
      },
    },
  },
  {
    section: "funnel",
    id: "m6-funnel-neg-filter-arity",
    title: "负例：EQ 过滤值数量不符",
    kind: "negative",
    expectErrorCode: 1002000000,
    dsl: {
      mode: "FUNNEL",
      filters: [{ fieldId: "dim.customer_tier", operator: "EQ", values: ["gold", "normal"] }],
      analysisParams: {
        steps: ["ORDER_PLACED", "REFUND"],
        cohortWindow: { timeExpressionRaw: "近 60 天" },
        stepWindow: "7d",
        subjectPropertyId: SUBJECT,
        deduplication: "first_occurrence",
        conversionDenominator: "previous_step",
      },
    },
  },
];

const SECTION = process.argv.find((item) => item.startsWith("--section="))?.split("=")[1]
  ?? argOf("--section", "retention");
const ALL_CASES = [...CASES, ...ATTRIBUTION_CASES, ...FUNNEL_CASES];
const ACTIVE = ALL_CASES.filter((item) => item.section === SECTION);

// request_id 必须随运行变化：request_state 以 request_id 为幂等键（ADR-0004）
const RUN = new Date().toISOString().replace(/[-:.TZ]/g, "").slice(0, 14);

async function compileOnce(dsl, requestId) {
  const body = { ...dsl, manifestId: MANIFEST_ID };
  const response = await fetch(`${BASE}/api/v1/query/compile`, {
    method: "POST",
    headers: { ...HEADERS, "X-Request-Id": `m6-gold-${RUN}-${requestId}` },
    body: JSON.stringify(body),
  });
  const payload = await response.json();
  if (payload.code !== 0) {
    return { errorCode: payload.code, errorMessage: payload.msg ?? "" };
  }
  return {
    sql: payload.data.sql,
    bindValues: payload.data.bindValues,
    joins: payload.data.joins ?? [],
  };
}

async function main() {
  if (MODE === "backfill") {
    // 按 section 增量回填：保留其他 section 的已冻结案例（gold 是单文件多段，不能整段覆盖）
    let kept = [];
    try {
      const existing = JSON.parse(readFileSync(GOLD, "utf8"));
      kept = (existing.cases ?? []).filter((item) => item.section !== SECTION);
    } catch {
      kept = [];
    }
    const gold = { manifestId: MANIFEST_ID, generatedAt: new Date().toISOString(), cases: [...kept] };
    for (const testCase of ACTIVE) {
      const first = await compileOnce(testCase.dsl, `${testCase.id}-a`);
      const second = await compileOnce(testCase.dsl, `${testCase.id}-b`);
      if (testCase.kind === "negative") {
        const ok = first.errorCode === testCase.expectErrorCode;
        gold.cases.push({
          id: testCase.id,
          title: testCase.title,
          section: testCase.section,
          kind: "negative",
          dsl: testCase.dsl,
          expectedErrorCode: testCase.expectErrorCode,
          actualErrorCode: first.errorCode ?? null,
          errorMessage: first.errorMessage ?? "",
          status: ok ? "supported" : "unexpected",
        });
        continue;
      }
      if (first.errorCode) {
        gold.cases.push({
          id: testCase.id,
          title: testCase.title,
          section: testCase.section,
          kind: "sql",
          dsl: testCase.dsl,
          status: "unexpected",
          actualErrorCode: first.errorCode,
          errorMessage: first.errorMessage ?? "",
        });
        continue;
      }
      if (JSON.stringify(first) !== JSON.stringify(second)) {
        gold.cases.push({
          id: testCase.id, title: testCase.title, section: testCase.section,
          kind: "repro", dsl: testCase.dsl, status: "mismatch",
        });
        continue;
      }
      gold.cases.push({
        id: testCase.id,
        title: testCase.title,
        section: testCase.section,
        kind: "sql",
        dsl: testCase.dsl,
        sqlExpected: first.sql,
        joinsExpected: first.joins,
        // 绑定值含观察锚点（随基准日变化），只作回填参考、不参与逐字比对
        bindValuesSample: first.bindValues,
        status: "compiled",
      });
    }
    mkdirSync(dirname(GOLD), { recursive: true });
    writeFileSync(GOLD, `${JSON.stringify(gold, null, 2)}\n`, "utf8");
    const ok = gold.cases.filter((item) => item.section === SECTION
      && (item.status === "compiled" || item.status === "supported"));
    console.log(`backfill[${SECTION}]: ${ok.length}/${ACTIVE.length} 固化 → ${GOLD}（保留其他段 ${kept.length} 条）`);
    const bad = gold.cases.filter((item) => item.status === "unexpected" || item.status === "mismatch");
    for (const item of bad) {
      console.error(`  UNEXPECTED ${item.id}: ${item.actualErrorCode ?? "repro mismatch"} ${item.errorMessage ?? ""}`);
    }
    process.exit(bad.length > 0 ? 1 : 0);
  }

  const gold = JSON.parse(readFileSync(GOLD, "utf8"));
  let pass = 0;
  let fail = 0;
  for (const expected of gold.cases) {
    if (expected.section !== SECTION) {
      continue;
    }
    expected.dsl.manifestId = MANIFEST_ID;
    if (expected.kind === "negative") {
      const actual = await compileOnce(expected.dsl, `m6-chk-${expected.id}`);
      const ok = actual.errorCode === expected.expectedErrorCode;
      console.log(`  ${ok ? "OK  " : "FAIL"} ${expected.id}（错误码 ${actual.errorCode}）`);
      ok ? pass++ : fail++;
      continue;
    }
    if (expected.kind !== "sql") {
      console.log(`  SKIP ${expected.id}（${expected.kind}）`);
      continue;
    }
    const first = await compileOnce(expected.dsl, `m6-chk-${expected.id}-a`);
    const second = await compileOnce(expected.dsl, `m6-chk-${expected.id}-b`);
    const reproducible = JSON.stringify(first) === JSON.stringify(second);
    const ok = !first.errorCode && first.sql === expected.sqlExpected && reproducible;
    console.log(`  ${ok ? "OK  " : "FAIL"} ${expected.id}${ok ? "" : `（${first.errorCode ?? "SQL 不一致"}）`}`);
    ok ? pass++ : fail++;
  }
  console.log(`check[${SECTION}]: ${pass} 通过 / ${fail} 失败`);
  process.exit(fail > 0 ? 1 : 0);
}

await main();
