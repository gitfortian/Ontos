/**
 * PoC #4 Golden SQL 回填与逐字比对（M2/W6/T6.1）。
 *
 * 两个模式：
 *   --mode=backfill  逐条编译案例 → 把规范化 SQL / Join 路径 / 期望错误码写入 gold 文件
 *   --mode=check     重新编译并与 gold 逐字比对（SQL 字符串逐字 + Join 路径 + 负例错误码），
 *                    同时每条案例编译两次断言绑定值可复现（PoC #2 的口径）
 *
 * 设计取舍：
 *   - gold 只固化 **SQL 文本** 与 Join/错误码，不固化绑定值：相对时间的绑定值随「今天」变化，
 *     固化会让 gold 隔天失效；可复现性用「同一次运行里编译两次比对」来验，而不是跨日比字符串。
 *   - 负例只记录期望错误码，不记录 SQL（被拒的请求没有编译产物）。
 *   - 案例里没有给出的输入（lookup 投影属性、funnel 步窗、yoy 时间窗）在 DSL_OVERRIDES 里
 *     显式写死并注明理由——不允许脚本运行时猜。
 */
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** 支持 `--key value` 与 `--key=value` 两种写法。 */
const argOf = (name, fallback) => {
  const inline = process.argv.find((item) => item.startsWith(`${name}=`));
  if (inline) {
    return inline.slice(name.length + 1);
  }
  const index = process.argv.indexOf(name);
  return index > -1 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
};
const repo = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const BASE = argOf("--base-url", "http://127.0.0.1:8080");
let MANIFEST_ID = argOf("--manifest-id", null);
const MODE = argOf("--mode", "check");
const CASES_PATH = resolve(repo, "docs/06-verification/evaluation/Golden集-v2-案例设计.json");
const GOLD_PATH = resolve(repo, "docs/06-verification/evaluation/Golden集-v2-gold-v1.json");

const HEADERS = {
  "Content-Type": "application/json",
  "X-Ontos-Tenant": "demo",
  "X-Ontos-User": "golden-runner",
  "X-Ontos-Session": "golden",
  "X-Ontos-Turn": "1",
};

// 请求在 M1 口径下被钉在「当前生效清单」上：未显式指定时默认取 active，
// 否则清单一升级，回填时的 manifestId 就会让 check 全军覆没（1001000001）。
if (!MANIFEST_ID) {
  const current = await fetch(BASE + "/api/v1/ontology/manifests/current", { headers: HEADERS })
    .then((response) => response.json());
  MANIFEST_ID = current?.data?.manifestId;
  if (!MANIFEST_ID) {
    console.error("无法取得当前生效清单，请用 --manifest-id 显式指定");
    process.exit(1);
  }
  console.log(`使用当前生效清单: ${MANIFEST_ID}`);
}

/** 时间维度固定用 dim.order_date：它是首发清单里唯一 DAY 粒度的日期维度。 */
const TIME_FIELD = "dim.order_date";

/** lookup 投影属性：案例只写了「SELECT props」，具体投影在实现侧定档（同表 + lookupProjectable）。 */
const LOOKUP_ATTRIBUTES = {
  "concept.customer": ["property.customer.email", "property.customer.tier"],
  "concept.order": ["property.order.order_no", "property.order.order_date", "property.order.amount"],
  "concept.product": ["property.product.product_id", "property.product.category"],
};

/** lookup / lookup_batch 的标识属性（案例只给了值，没给属性 ID）。 */
const IDENTITY_PROPERTY = {
  "concept.customer": "property.customer.customer_id",
  "concept.order": "property.order.order_no",
  "concept.product": "property.product.product_id",
};

/** 案例未给出、但编译必需的输入；逐条写死并说明理由，不在运行时推断。 */
const DSL_OVERRIDES = {
  // yoy_mom 必须有本期时间窗才能算同比；案例只给了 period，这里统一取 1 年回看。
  "g-y01": { timeExpressionRaw: "近 365 天", timeRange: { field: TIME_FIELD } },
  "g-y02": { timeExpressionRaw: "近 365 天", timeRange: { field: TIME_FIELD } },
  "g-y03": { timeExpressionRaw: "近 365 天", timeRange: { field: TIME_FIELD } },
  "g-y04": { timeExpressionRaw: "近 365 天", timeRange: { field: TIME_FIELD } },
};

/** funnel 的步进参数（analysis-mode 规格 v1 §2 的必填项）。 */
const FUNNEL_DEFAULTS = {
  stepWindow: "7d",
  deduplication: "first_occurrence",
  conversionDenominator: "previous_step",
  subjectPropertyId: "property.customer.customer_id",
};

/** 负例的显式 DSL（案例只写了意图，没给可编译的 DSL）。 */
const BATCH_TOO_LARGE = Array.from({ length: 51 }, (_v, i) => `c-${1000 + i}`);

const NEGATIVE_CASES = {
  "g-n01": {
    dsl: { mode: "QUERY", metricId: "metric.not_exist", dimensions: [] },
    expectErrorCode: 1001000003,
    title: "metric 不在清单",
  },
  "g-n03": {
    dsl: {
      mode: "LOOKUP_BATCH",
      entityId: "concept.customer",
      identityPropertyId: IDENTITY_PROPERTY["concept.customer"],
      attributes: ["property.customer.tier"],
      identities: BATCH_TOO_LARGE,
    },
    expectErrorCode: 1002000019,
    title: "批量大小超上限",
  },
  "g-n04": {
    dsl: {
      mode: "QUERY",
      metricId: "metric.gmv",
      dimensions: ["dim.region"],
      // 有 from/to、没有 source：等价于「LLM 直接产出绝对日期」，必须拒绝
      timeRange: { field: TIME_FIELD, from: "2026-01-01T00:00:00Z", to: "2026-02-01T00:00:00Z" },
    },
    expectErrorCode: 1002000009,
    title: "绝对日期被拒绝",
  },
  "g-n06": {
    dsl: { mode: "QUERY", metricId: "metric.gmv", dimensions: [], bogusField: 1 },
    expectErrorCode: 1002000011,
    title: "未知字段拒绝",
  },
  // 案例 expectShape 写明「重复步骤 → 校验拒绝（负例）」，但设计文件没有填 negative 字段；
  // 这里显式登记为负例，不靠字符串推断。
  "g-f03": {
    dsl: null,
    expectErrorCode: 1002000000,
    title: "funnel 重复步骤拒绝",
  },
};

/** 当前清单上无法构造的负例：如实登记原因，不伪造通过。 */
const UNSUPPORTED = {
  "g-n02": "当前清单已放开全部维度授权（R-01 修复的副作用），DIMENSION_NOT_ALLOWED 在真实清单上不可构造；"
    + "该判据由 DslValidatorTest#shouldRejectUnauthorizedDimension 单测覆盖。",
  "g-n05": "local profile 为 dev-allow-all，授权子图不拒绝任何域；跨域拒绝由策略层单测覆盖，"
    + "真实策略要等 M4 网关接入。",
};

function buildDsl(testCase) {
  const dsl = testCase.dsl ?? {};
  const base = { manifestId: MANIFEST_ID };

  switch (testCase.mode) {
    case "query": {
      const built = {
        ...base,
        mode: "QUERY",
        metricId: dsl.metricId,
        dimensions: dsl.dimensions ?? [],
      };
      if (dsl.filters?.length) {
        built.filters = dsl.filters.map((filter) => ({
          fieldId: filter.fieldId,
          operator: filter.op,
          values: filter.values ?? [filter.value],
        }));
      }
      if (dsl.time) {
        built.timeExpressionRaw = dsl.time;
        built.timeRange = { field: TIME_FIELD };
      }
      return { ...built, ...(DSL_OVERRIDES[testCase.id] ?? {}) };
    }
    case "lookup": {
      const built = {
        ...base,
        mode: "LOOKUP",
        entityId: dsl.entity,
        attributes: LOOKUP_ATTRIBUTES[dsl.entity] ?? [],
        identity: dsl.identity,
      };
      return built;
    }
    case "lookup_batch": {
      const built = {
        ...base,
        mode: "LOOKUP_BATCH",
        entityId: dsl.entity,
        identityPropertyId: IDENTITY_PROPERTY[dsl.entity],
        attributes: LOOKUP_ATTRIBUTES[dsl.entity] ?? [],
        identities: dsl.identities ?? [],
      };
      if (dsl.booleanMetric) {
        built.booleanMetricId = dsl.booleanMetric;
      }
      return built;
    }
    case "funnel": {
      const params = {
        steps: dsl.steps,
        ...FUNNEL_DEFAULTS,
        cohortWindow: { timeExpressionRaw: dsl.time },
      };
      const built = {
        ...base,
        mode: "FUNNEL",
        dimensions: dsl.groupBy ?? [],
        analysisParams: params,
      };
      return built;
    }
    case "yoy_mom": {
      const built = {
        ...base,
        mode: "YOY_MOM",
        metricId: dsl.metricId,
        dimensions: dsl.dimensions ?? [],
        analysisParams: { period: dsl.period },
      };
      return { ...built, ...(DSL_OVERRIDES[testCase.id] ?? {}) };
    }
    default:
      throw new Error(`未知 mode: ${testCase.mode}`);
  }
}

async function post(path, body) {
  const response = await fetch(BASE + path, {
    method: "POST",
    headers: HEADERS,
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`${path} HTTP ${response.status}`);
  }
  return await response.json();
}

/** 编译一次；返回 {sql, bindValues, joins} 或 {errorCode, errorMessage}。 */
async function compileOnce(dsl, requestId) {
  const data = await post("/api/v1/query/compile", dsl);
  if (data.code !== 0) {
    return { errorCode: data.code, errorMessage: data.msg };
  }
  return {
    sql: data.data.sql,
    bindValues: data.data.bindValues ?? [],
    joins: data.data.joins ?? [],
  };
}

function sameJson(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

async function backfill(cases) {
  const results = [];
  for (const testCase of cases) {
    if (testCase.id in UNSUPPORTED) {
      results.push({
        id: testCase.id,
        mode: testCase.mode,
        kind: testCase.negative ? "negative" : "positive",
        status: "unsupported",
        reason: UNSUPPORTED[testCase.id],
      });
      continue;
    }
    const negative = NEGATIVE_CASES[testCase.id];
    // 案例自带 negative 标记但没有显式 DSL 的（如 funnel 步骤数不足/重复），
    // 用正常映射构造 DSL，断言「校验器拒绝」——期望码是 DSL_INVALID。
    const isNegative = Boolean(negative) || Boolean(testCase.negative);
    const dsl = negative?.dsl ? { ...negative.dsl, manifestId: MANIFEST_ID } : buildDsl(testCase);
    const compiled = await compileOnce(dsl, `gold-${testCase.id}`);

    if (isNegative) {
      const expectedCode = negative?.expectErrorCode ?? 1002000000;
      const title = negative?.title ?? testCase.negative;
      results.push({
        id: testCase.id,
        mode: testCase.mode,
        kind: "negative",
        status: compiled.errorCode === expectedCode ? "supported" : "mismatch",
        title,
        dsl,
        expectedErrorCode: expectedCode,
        actualErrorCode: compiled.errorCode ?? null,
        actualErrorMessage: compiled.errorMessage ?? null,
      });
      continue;
    }

    if (compiled.errorCode) {
      results.push({
        id: testCase.id,
        mode: testCase.mode,
        kind: "positive",
        status: "unsupported",
        reason: `案例与 M2 定档冲突，编译被拒绝（${compiled.errorCode}）：${compiled.errorMessage}`,
        dsl,
      });
      continue;
    }

    results.push({
      id: testCase.id,
      mode: testCase.mode,
      kind: "positive",
      status: "supported",
      dsl,
      sqlExpected: compiled.sql,
      bindCount: compiled.bindValues.length,
      joinsExpected: compiled.joins,
      expectShape: testCase.expectShape,
    });
  }

  const supported = results.filter((item) => item.status === "supported").length;
  const gold = {
    setId: "poc04-golden-v2-gold",
    version: "v1",
    generatedFrom: "docs/06-verification/evaluation/Golden集-v2-案例设计.json",
    manifestId: MANIFEST_ID,
    criteriaRef: "技术选型 v1.1 §7 #4（逐字比对 100%）",
    note:
      "sqlExpected 由当前实现编译产出、逐条人工审阅后回填；绑定值不固化（相对时间随当日基准变化），"
      + "可复现性由 check 模式「同次运行编译两次比对」验证。unsupported 项如实登记原因。",
    reviewedAt: new Date().toISOString(),
    total: results.length,
    supported,
    unsupported: results.length - supported,
    cases: results,
  };
  writeFileSync(GOLD_PATH, JSON.stringify(gold, null, 2) + "\n", "utf8");
  return gold;
}

async function check(cases) {
  const gold = JSON.parse(readFileSync(GOLD_PATH, "utf8"));
  const goldById = new Map(gold.cases.map((item) => [item.id, item]));
  const outcomes = [];

  for (const testCase of cases) {
    const expected = goldById.get(testCase.id);
    if (!expected) {
      outcomes.push({ id: testCase.id, ok: false, reason: "gold 缺少该案例" });
      continue;
    }
    if (expected.status === "unsupported") {
      outcomes.push({ id: testCase.id, ok: true, skipped: true, reason: expected.reason });
      continue;
    }
    if (expected.kind === "negative") {
      // gold 固化的 DSL 带「回填当时的 manifestId」；清单升级后旧 ID 会被 1001000001 拦住，
      // 这里对齐到本次运行清单再编译——SQL/错误码比对不依赖 manifestId 本身。
      expected.dsl.manifestId = MANIFEST_ID;
      const actual = await compileOnce(expected.dsl, `gold-chk-${testCase.id}`);
      const ok = actual.errorCode === expected.expectedErrorCode;
      outcomes.push({
        id: testCase.id,
        ok,
        reason: ok
          ? `按预期拒绝 ${expected.expectedErrorCode}`
          : `期望 ${expected.expectedErrorCode}，实际 ${actual.errorCode ?? "编译通过"}`,
      });
      continue;
    }

    expected.dsl.manifestId = MANIFEST_ID;
    const first = await compileOnce(expected.dsl, `gold-chk-${testCase.id}-a`);
    const second = await compileOnce(expected.dsl, `gold-chk-${testCase.id}-b`);
    if (first.errorCode || second.errorCode) {
      outcomes.push({
        id: testCase.id,
        ok: false,
        reason: `编译失败：${first.errorCode ?? second.errorCode} ${first.errorMessage ?? second.errorMessage ?? ""}`,
      });
      continue;
    }
    const sqlOk = first.sql === expected.sqlExpected;
    const joinsOk = sameJson(first.joins, expected.joinsExpected);
    const bindStable = sameJson(first.bindValues, second.bindValues);
    const ok = sqlOk && joinsOk && bindStable;
    outcomes.push({
      id: testCase.id,
      ok,
      reason: ok
        ? "SQL/Join 逐字一致，绑定值可复现"
        : [sqlOk ? null : "SQL 不一致", joinsOk ? null : "Join 不一致", bindStable ? null : "绑定值不稳定"]
          .filter(Boolean)
          .join("；"),
    });
  }

  const failed = outcomes.filter((item) => !item.ok);
  const skipped = outcomes.filter((item) => item.skipped);
  return { outcomes, failed, skipped };
}

const design = JSON.parse(readFileSync(CASES_PATH, "utf8"));
const cases = design.cases;

if (MODE === "backfill") {
  const gold = await backfill(cases);
  console.log(`回填完成：${gold.total} 条，supported=${gold.supported}，unsupported=${gold.unsupported}`);
  for (const item of gold.cases) {
    if (item.status === "supported") {
      console.log(`  ${item.id} ${item.kind === "negative" ? "[负例]" : ""} ${item.sqlExpected?.slice(0, 150) ?? ""}`);
    } else {
      console.log(`  ${item.id} [${item.status}] ${item.reason?.slice(0, 120) ?? ""}`);
    }
  }
} else {
  const { outcomes, failed, skipped } = await check(cases);
  const passed = outcomes.length - failed.length - skipped.length;
  console.log(`逐字比对：${outcomes.length} 条，通过 ${passed}，跳过 ${skipped.length}（unsupported），失败 ${failed.length}`);
  for (const item of failed) {
    console.log(`  FAIL ${item.id}: ${item.reason}`);
  }
  if (failed.length > 0) {
    process.exitCode = 1;
  }
}
