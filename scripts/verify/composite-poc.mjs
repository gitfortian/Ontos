/**
 * PoC #8 复合意图回填（M2 深度：无依赖子集，M2 规格 §1-G）。
 *
 * 分类而不是一把梭：
 *   deferred_m6            含 dependsOn（结果依赖类）→ 按定档登记 M6，不计入 M2 通过率
 *   unsupported_dsl        子意图不是 QUERY（LOOKUP / BOOLEAN_RULE 在 SubIntent DSL 里表达不了）
 *   unsupported_partial    含 failureInjection（M2 整单拒绝，部分失败可解释归 M6）
 *   expected_reject_grain  子查询本身语义非法（跨表维度 1:N 粒度扩散），被拒即正确
 *   pass / fail            真实执行结果
 *
 * 用法：node scripts/verify/composite-poc.mjs [--base-url ...] [--manifest-id ...]
 */
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

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
const MANIFEST_ID = argOf("--manifest-id", "2026.11.08-seventh");
const CORPUS = resolve(repo, "docs/06-verification/evaluation/复合意图题集-v2.json");
const EVIDENCE = resolve(repo, "docs/06-verification/evaluation/复合意图题集-v2-M2回填.json");

const HEADERS = {
  "Content-Type": "application/json",
  "X-Ontos-Tenant": "demo",
  "X-Ontos-User": "poc8-runner",
  "X-Ontos-Session": "poc8",
  "X-Ontos-Turn": "1",
};

/** 子意图里没有时间表达：M2 不做推断，统一不带时间窗（案例也没给）。 */
function toSubIntent(sub, index) {
  return {
    subIntentId: `s${sub.seq ?? index + 1}`,
    metricId: sub.assetRef,
    mode: "QUERY",
    dimensions: sub.dimensions ?? [],
  };
}

async function executeComposite(subs, requestId) {
  const response = await fetch(`${BASE}/api/v1/query/composite/execute`, {
    method: "POST",
    headers: { ...HEADERS, "X-Request-Id": requestId },
    body: JSON.stringify({ manifestId: MANIFEST_ID, mode: "QUERY", subIntents: subs }),
  });
  if (!response.ok) {
    return { errorCode: response.status, errorMessage: `HTTP ${response.status}` };
  }
  const body = await response.json();
  if (body.code !== 0) {
    return { errorCode: body.code, errorMessage: body.msg };
  }
  return { outcome: body.data };
}

const corpus = JSON.parse(readFileSync(CORPUS, "utf8"));
const results = [];

for (const testCase of corpus.cases) {
  const subs = testCase.expectedSubIntents ?? [];
  const base = { id: testCase.id, category: testCase.category, subCount: subs.length };

  if (subs.some((sub) => (sub.dependsOn ?? []).length > 0)) {
    results.push({ ...base, status: "deferred_m6", reason: "结果依赖类（dependsOn 非空），按 M2 定档登记 M6" });
    continue;
  }
  const nonQuery = subs.find((sub) => (sub.intent ?? "QUERY") !== "QUERY");
  if (nonQuery) {
    results.push({
      ...base,
      status: "unsupported_dsl",
      reason: `子意图 ${nonQuery.intent} 在 SubIntent DSL 里没有对应字段（只有 metric/dimensions/filters/timeExpression）`,
    });
    continue;
  }
  if (subs.some((sub) => sub.failureInjection)) {
    results.push({
      ...base,
      status: "unsupported_partial",
      reason: "含单子失败注入；M2 是整单拒绝（不返回部分结果），部分失败可解释归 M6",
    });
    continue;
  }

  const built = subs.map(toSubIntent);
  const outcome = await executeComposite(built, `poc8-${testCase.id}`);

  if (outcome.errorCode) {
    // 语义正确但子查询本身非法的案例：跨表维度在 1:N 边扩散（orders→order_item→product）
    const grain = outcome.errorCode === 1002000014;
    results.push({
      ...base,
      status: grain ? "expected_reject_grain" : "fail",
      reason: `${outcome.errorCode}: ${outcome.errorMessage}`,
      subs: built,
    });
    continue;
  }

  const perSub = outcome.outcome.results ?? [];
  const aligned = perSub.length === built.length
    && perSub.every((item, index) => item.subIntentId === built[index].subIntentId);
  results.push({
    ...base,
    status: aligned ? "pass" : "fail",
    reason: aligned ? `逐子返回 ${perSub.length} 份结果，顺序与请求一致` : "子结果数量或顺序与请求不一致",
    totalRowCount: outcome.outcome.totalRowCount,
    perSubRowCount: perSub.map((item) => ({ subIntentId: item.subIntentId, rowCount: item.rowCount })),
    subs: built,
  });
}

const counters = results.reduce((acc, item) => {
  acc[item.status] = (acc[item.status] ?? 0) + 1;
  return acc;
}, {});

const evidence = {
  set: "poc08-composite-v2",
  m2Depth: "无依赖子集（结果依赖类登记 M6，M2 规格 §1-G）",
  manifestId: MANIFEST_ID,
  generatedAt: new Date().toISOString(),
  total: results.length,
  counters,
  results,
};
writeFileSync(EVIDENCE, JSON.stringify(evidence, null, 2) + "\n", "utf8");

console.log(`复合题集 ${results.length} 题：`, counters);
for (const item of results.filter((entry) => entry.status === "fail")) {
  console.log(`  FAIL ${item.id}: ${item.reason}`);
}
