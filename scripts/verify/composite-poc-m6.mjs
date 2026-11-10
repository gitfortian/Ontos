/**
 * PoC #8 复跑（M6/W4/T4.5 深度）：30 题全部按 M6 行为预期判定。
 *
 * 相对 M2 回填（composite-poc.mjs，证据 复合意图题集-v2-M2回填.json）的分类升级：
 *   result_dependent   dependsOn + resultRef 结构化注入 → 真实执行（M2 时是 deferred_m6）
 *   failure_single     单子失败注入（引用不存在的维度）→ 部分失败可解释：失败子带 errorCode、
 *                      其余子照常返回（M2 时是 unsupported_partial——整单拒绝）
 *   unsupported_dsl    LOOKUP / BOOLEAN_RULE 子意图 → 决策 7 维持拒绝：请求被拒 = pass
 *   expected_reject_grain / pass / fail 同 M2 语义
 *
 * 用法：node scripts/verify/composite-poc-m6.mjs [--base-url ...] [--manifest-id ...]
 * 证据：docs/06-verification/evaluation/复合意图题集-v2-M6复跑.json
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
let MANIFEST_ID = argOf("--manifest-id", null);
const CORPUS = resolve(repo, "docs/06-verification/evaluation/复合意图题集-v2.json");
const EVIDENCE = resolve(repo, "docs/06-verification/evaluation/复合意图题集-v2-M6复跑.json");

const HEADERS = {
  "Content-Type": "application/json",
  "X-Ontos-Tenant": "demo",
  "X-Ontos-User": "poc8-runner",
  "X-Ontos-Session": "poc8",
  "X-Ontos-Turn": "1",
};

if (!MANIFEST_ID) {
  MANIFEST_ID = await fetch(BASE + "/api/v1/ontology/manifests/current", {
    headers: { "Content-Type": "application/json", "X-Ontos-Tenant": "demo",
      "X-Ontos-User": "verify-runner", "X-Ontos-Session": "verify", "X-Ontos-Turn": "1" },
  })
    .then((response) => response.json())
    .then((body) => body?.data?.manifestId);
  if (!MANIFEST_ID) {
    console.error("无法取得当前生效清单，请用 --manifest-id 显式指定");
    process.exit(1);
  }
  console.log(`使用当前生效清单: ${MANIFEST_ID}`);
}

/**
 * M6 深度构造：
 *   - dependsOn 非空 → 依赖者补 resultRef（ordered_top 第 1 行取第一维度列注入 EQ filter）——
 *     derive 语义的自动翻译归编排/LLM（决策 6），本脚本按已结构化提案验证注入链路。
 *   - failureInjection: N → 第 N 子引用不存在的维度（必然编译拒绝），验证部分失败可解释。
 */
function buildSubIntent(sub, index) {
  const built = {
    subIntentId: `s${sub.seq ?? index + 1}`,
    metricId: sub.assetRef,
    // 按题面意图构造——LOOKUP/BOOLEAN_RULE 保持原样（决策 7：请求被拒 = 符合预期）
    mode: sub.intent ?? "QUERY",
    dimensions: (sub.dimensions ?? []).map((dimension) => dimension),
  };
  if (sub.failureInjection) {
    // 失败注入：不存在的语义资产 → prepareWith/校验必拒（fail-closed 无新码）
    built.dimensions = ["dim.nonexistent_injection"];
  }
  if ((sub.dependsOn ?? []).length > 0) {
    built.dependsOn = sub.dependsOn.map((seq) => `s${seq}`);
    const dimensionColumn = (sub.dimensions ?? [])[0] ?? "region";
    built.filters = [{ fieldId: dimensionColumn, operator: "EQ", values: [] }];
    built.resultRef = {
      fromSubIntentId: `s${sub.dependsOn[0]}`,
      selector: { type: "ordered_top", row: 1, column: dimensionColumn },
      inject: { type: "filter_value", filterIndex: 0 },
    };
  }
  return built;
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

const runId = `r${Date.now()}`;
const corpus = JSON.parse(readFileSync(CORPUS, "utf8"));
const results = [];

for (const testCase of corpus.cases) {
  const subs = testCase.expectedSubIntents ?? [];
  const base = { id: testCase.id, category: testCase.category, subCount: subs.length };

  // 决策 7（ADR-0013 同型口径）：LOOKUP / BOOLEAN_RULE 子意图维持拒绝——请求被拒 = 符合预期
  const nonQuery = subs.find((sub) => (sub.intent ?? "QUERY") !== "QUERY");
  if (nonQuery && !subs.some((sub) => sub.failureInjection)) {
    const built = subs.map(buildSubIntent);
    const outcome = await executeComposite(built, `poc8m6-${runId}-${testCase.id}`);
    results.push({
      ...base,
      status: outcome.errorCode ? "pass_rejected_by_design" : "fail",
      reason: outcome.errorCode
        ? `非 QUERY 子意图被拒（决策 7 维持）：${outcome.errorCode}: ${String(outcome.errorMessage).slice(0, 80)}`
        : "非 QUERY 子意图不应被执行（决策 7）",
      subs: built,
    });
    continue;
  }

  const built = subs.map(buildSubIntent);
  const outcome = await executeComposite(built, `poc8m6-${runId}-${testCase.id}`);

  if (outcome.errorCode) {
    // 整单拒绝的合法形状：跨表粒度扩散（expected_reject_grain）；决策 7 同型——
    // BOOLEAN_RULE 意图无法反序列化进 SubIntent.mode 闭枚举，请求级拒绝（c16）
    const grain = outcome.errorCode === 1002000014;
    const nonQueryDesign = outcome.errorCode === 1002000000 && nonQuery;
    results.push({
      ...base,
      status: grain ? "expected_reject_grain"
        : nonQueryDesign ? "pass_rejected_by_design" : "fail",
      reason: grain ? outcome.errorCode + ": 跨表粒度扩散 fail-closed（ADR-0016）"
        : nonQueryDesign ? "非 QUERY 子意图在 DSL 层被拒（决策 7 同型）："
            + outcome.errorCode
        : outcome.errorCode + ": " + String(outcome.errorMessage).slice(0, 90),
      subs: built,
    });
    continue;
  }

  const perSub = outcome.outcome.results ?? [];
  const failureInjections = subs.filter((sub) => sub.failureInjection).length;

  if (failureInjections > 0) {
    // 部分失败可解释（T4.3 验收）：响应 VO 平铺——失败子以 errorCode 识别，成功子有 rowCount
    const injected = perSub.filter((item) => item.errorCode);
    const healthy = perSub.filter((item) => !item.errorCode);
    // 失败码全部可归因（注入失败 1001000003 / 1001000004；粒度拒绝 1002000014 = ADR-0016 预期；
    // DSL 层拒绝 1002000000 = 决策 7 同型口径——BOOLEAN_RULE 意图在反序列化层即拒）
    const expectedCodes = new Set([1001000003, 1001000004, 1002000014, 1002000000]);
    const allExpected = injected.every((item) => expectedCodes.has(item.errorCode));
    const shapeOk = injected.length >= failureInjections
      && healthy.length === perSub.length - injected.length && allExpected;
    const detail = shapeOk
      ? "失败子 " + injected.map((item) => item.subIntentId + "#" + item.errorCode).join(",")
        + "（含预期粒度拒绝）；健康子 " + healthy.length + " 个照常返回"
      : "失败子形状不符：" + JSON.stringify(perSub.map((item) => ({
          id: item.subIntentId, code: item.errorCode })));
    results.push({
      ...base,
      status: shapeOk ? "pass_partial_failure" : "fail",
      reason: detail,
      perSub: perSub.map((item) => ({
        subIntentId: item.subIntentId,
        errorCode: item.errorCode ?? null,
        rowCount: item.result?.rowCount ?? null,
      })),
      subs: built,
    });
    continue;
  }

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
  depth: "M6/W4/T4.5（依赖注入 + 部分失败可解释 + 决策 7 拒绝复验）",
  manifestId: MANIFEST_ID,
  generatedAt: new Date().toISOString(),
  total: results.length,
  counters,
  results,
};

writeFileSync(EVIDENCE, JSON.stringify(evidence, null, 2) + "\n");
console.log(`写证据: ${EVIDENCE}`);
console.table(counters);
const failures = results.filter((item) => item.status === "fail");
if (failures.length > 0) {
  console.error(`FAIL ${failures.length} 题:`);
  failures.forEach((item) => console.error(`  ${item.id} [${item.category}] ${item.reason}`));
  process.exit(1);
}
console.log("PoC #8 M6 复跑：30 题全部符合预期行为");
