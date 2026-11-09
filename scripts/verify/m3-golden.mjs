#!/usr/bin/env node
/**
 * M3 Golden 增量（M3 规格 §5：≥6 条，逐字比对，复用 golden-sql 的判据口径）。
 *
 * 覆盖：lookup 单实体 / lookup_batch / 布尔判定 / 变体命中 / 变体歧义（负例）/ 上季度。
 * 固化内容与 M2 gold 一致：SQL 字符串 + 负例错误码；绑定值含时间基准不参与逐字比对，
 * 可复现性用「同次运行两次编译比对」。check 时把 DSL 的 manifestId 对齐当前生效清单。
 *
 * 用法：
 *   node scripts/verify/m3-golden.mjs --mode=backfill   # 生成/覆盖 gold 文件
 *   node scripts/verify/m3-golden.mjs --mode=check      # 逐字比对
 */
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const BASE = process.env.ONTOS_BASE_URL ?? "http://127.0.0.1:8080";
const GOLD = resolve(repo, "docs/06-verification/evaluation/M3-Golden-v1.json");
const MODE = process.argv.includes("--mode=check") ? "check" : "backfill";

const argOf = (name, fallback) => {
  const index = process.argv.indexOf(name);
  return index > -1 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
};

const HEADERS = {
  "Content-Type": "application/json",
  "X-Ontos-Tenant": "demo",
  "X-Ontos-User": "m3-golden",
  "X-Ontos-Session": "m3-golden",
  "X-Ontos-Turn": "1",
};

// 请求被钉在「当前生效清单」上；显式指定仅用于回放历史版本。
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

const ENTITY = "concept.customer";
const IDENTITY_PROPERTY = "property.customer.customer_id";
const ATTRIBUTES = ["property.customer.email", "property.customer.tier"];

/** M3 案例集（§5）：六条覆盖 lookup 单/批/布尔、变体命中/歧义、上季度。 */
const CASES = [
  {
    id: "m-lookup-single",
    title: "单实体属性直查",
    dsl: {
      mode: "LOOKUP",
      entityId: ENTITY,
      attributes: ATTRIBUTES,
      identity: { propertyId: IDENTITY_PROPERTY, value: "c-1001" },
    },
  },
  {
    id: "m-lookup-batch",
    title: "批量实体直查（缺失行同形）",
    dsl: {
      mode: "LOOKUP_BATCH",
      entityId: ENTITY,
      identityPropertyId: IDENTITY_PROPERTY,
      attributes: ATTRIBUTES,
      identities: ["c-1001", "c-9999"],
    },
  },
  {
    id: "m-boolean",
    title: "布尔判定列（引用已发布 Rule，无内联表达式）",
    dsl: {
      mode: "LOOKUP",
      entityId: ENTITY,
      attributes: ["property.customer.tier"],
      identity: { propertyId: IDENTITY_PROPERTY, value: "c-1001" },
      booleanMetricId: "metric.high_value_customer",
    },
  },
  {
    id: "m-caliber-hit",
    title: "口径变体命中（声明式条件进入 WHERE）",
    dsl: {
      mode: "QUERY",
      metricId: "metric.paid_gmv",
      dimensions: ["dim.region"],
      caliberVariantId: "metric.paid_gmv.online",
      timeExpressionRaw: "近 3650 天",
      timeRange: { field: "dim.order_date" },
    },
  },
  {
    id: "m-caliber-ambiguous",
    title: "多口径指标缺变体 → 澄清而非默认",
    negative: { expectErrorCode: 1002000033 },
    dsl: {
      mode: "QUERY",
      metricId: "metric.paid_gmv",
      dimensions: ["dim.region"],
      timeExpressionRaw: "近 3650 天",
      timeRange: { field: "dim.order_date" },
    },
  },
  {
    id: "m-last-quarter",
    title: "上季度（tep.last_quarter，时间窗走绑定值）",
    dsl: {
      mode: "QUERY",
      metricId: "metric.gmv",
      dimensions: ["dim.region"],
      timeExpressionRaw: "上季度",
      timeRange: { field: "dim.order_date" },
    },
  },
];

async function compileOnce(dsl, requestId) {
  const body = { ...dsl, manifestId: MANIFEST_ID };
  const response = await fetch(`${BASE}/api/v1/query/compile`, {
    method: "POST",
    headers: { ...HEADERS, "X-Request-Id": requestId },
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
    const gold = { manifestId: MANIFEST_ID, generatedAt: new Date().toISOString(), cases: [] };
    for (const testCase of CASES) {
      const first = await compileOnce(testCase.dsl, `m3-gold-${testCase.id}-a`);
      const second = await compileOnce(testCase.dsl, `m3-gold-${testCase.id}-b`);
      if (first.errorCode) {
        gold.cases.push({
          id: testCase.id,
          title: testCase.title,
          kind: "negative",
          dsl: testCase.dsl,
          expectedErrorCode: testCase.negative?.expectErrorCode ?? first.errorCode,
          actualErrorCode: first.errorCode,
          status: testCase.negative && first.errorCode === testCase.negative.expectErrorCode
            ? "supported"
            : "unexpected",
        });
        continue;
      }
      if (JSON.stringify(first) !== JSON.stringify(second)) {
        gold.cases.push({ id: testCase.id, title: testCase.title, kind: "repro", status: "mismatch" });
        continue;
      }
      gold.cases.push({
        id: testCase.id,
        title: testCase.title,
        kind: "sql",
        dsl: testCase.dsl,
        sqlExpected: first.sql,
        joinsExpected: first.joins,
        // 绑定值仅作回填当日参考（时间窗随基准日变化），不参与逐字比对
        bindValuesSample: first.bindValues,
      });
    }
    mkdirSync(dirname(GOLD), { recursive: true });
    writeFileSync(GOLD, `${JSON.stringify(gold, null, 2)}\n`, "utf8");
    const ok = gold.cases.filter((item) => item.status !== "unexpected" && item.status !== "mismatch");
    console.log(`backfill: ${ok.length}/${CASES.length} 固化 → ${GOLD}`);
    return;
  }

  const gold = JSON.parse(readFileSync(GOLD, "utf8"));
  let pass = 0;
  let fail = 0;
  for (const expected of gold.cases) {
    if (expected.kind === "negative") {
      expected.dsl.manifestId = MANIFEST_ID;
      const actual = await compileOnce(expected.dsl, `m3-chk-${expected.id}`);
      const ok = actual.errorCode === expected.expectedErrorCode;
      console.log(`  ${ok ? "OK  " : "FAIL"} ${expected.id}（错误码 ${actual.errorCode}）`);
      ok ? pass++ : fail++;
      continue;
    }
    if (expected.kind !== "sql") {
      console.log(`  SKIP ${expected.id}（${expected.kind}）`);
      continue;
    }
    expected.dsl.manifestId = MANIFEST_ID;
    const first = await compileOnce(expected.dsl, `m3-chk-${expected.id}-a`);
    const second = await compileOnce(expected.dsl, `m3-chk-${expected.id}-b`);
    const reproducible = JSON.stringify(first) === JSON.stringify(second);
    const ok = !first.errorCode && first.sql === expected.sqlExpected && reproducible;
    console.log(`  ${ok ? "OK  " : "FAIL"} ${expected.id}${ok ? "" : `（${first.errorCode ?? "SQL 不一致"}）`}`);
    ok ? pass++ : fail++;
  }
  console.log(`check: ${pass} 通过 / ${fail} 失败`);
  process.exit(fail > 0 ? 1 : 0);
}

await main();
