/**
 * E5 对抗探针（M2/W6）：LLM 没有通道提交自由 SQL / 绝对日期 / 服务端回填字段（M2 规格 §6 E5）。
 *
 * 9 个攻击面，每个都必须被业务错误码拒绝——出现 500 或 code=0 即视为漏洞。
 *
 * 用法：node scripts/verify/adversarial-probe.mjs [--manifest-id ...]
 */
import { writeFileSync } from "node:fs";
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
const EVIDENCE = resolve(repo, "docs/06-verification/checklists/M2-E5-对抗探针-2026-11-08.json");

const HEADERS = {
  "Content-Type": "application/json",
  "X-Ontos-Tenant": "demo",
  "X-Ontos-User": "probe",
  "X-Ontos-Session": "probe",
  "X-Ontos-Turn": "1",
};

// 请求在 M1 口径下被钉在「当前生效清单」上：未显式指定时默认取 active，
// 否则清单一升级，脚本里写死的 manifestId 会让整批请求被 1001000001 拦住。
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

const base = { manifestId: MANIFEST_ID, mode: "QUERY", metricId: "metric.gmv", dimensions: ["dim.region"] };

const probes = [
  {
    id: "free-sql",
    title: "直接提交自由 SQL",
    path: "/api/v1/query/execute",
    body: { ...base, sql: "select * from orders" },
    expect: [1002000011],
  },
  {
    id: "forged-time-source",
    title: "客户端伪造 timeRange.source=EXPLICIT",
    path: "/api/v1/query/execute",
    body: {
      ...base,
      timeRange: {
        field: "dim.order_date",
        from: "2026-01-01T00:00:00Z",
        to: "2026-02-01T00:00:00Z",
        source: "EXPLICIT",
      },
    },
    expect: [1002000011],
  },
  {
    id: "absolute-date",
    title: "绝对日期无来源标记（LLM 直接产出）",
    path: "/api/v1/query/execute",
    body: {
      ...base,
      timeRange: { field: "dim.order_date", from: "2026-01-01T00:00:00Z", to: "2026-02-01T00:00:00Z" },
    },
    expect: [1002000009],
  },
  {
    id: "forged-resolved-joins",
    title: "客户端提交服务端回填的 resolvedJoins",
    path: "/api/v1/query/execute",
    body: {
      ...base,
      resolvedJoins: [
        { relationId: "x", table: "customer", leftColumn: "customer.id", rightColumn: "orders.customer_id" },
      ],
    },
    expect: [1002000011],
  },
  {
    id: "forged-time-pattern",
    title: "客户端提交 timePatternId / timeParserVersion",
    path: "/api/v1/query/execute",
    body: { ...base, timePatternId: "tep.hacked", timeParserVersion: "9.9" },
    expect: [1002000011],
  },
  {
    id: "unpublished-metric",
    title: "候选集外指标 ID",
    path: "/api/v1/query/execute",
    body: { ...base, metricId: "metric.hacked" },
    expect: [1001000003],
  },
  {
    id: "physics-name",
    title: "把物理表名当维度 ID",
    path: "/api/v1/query/execute",
    body: { ...base, dimensions: ["public.orders.amount"] },
    expect: [1001000003, 1001000005],
  },
  {
    id: "dependent-sub-intent",
    title: "结果依赖子意图（应拒绝转澄清）",
    path: "/api/v1/query/composite/execute",
    body: {
      manifestId: MANIFEST_ID,
      mode: "QUERY",
      subIntents: [
        { subIntentId: "s1", metricId: "metric.gmv" },
        { subIntentId: "s2", metricId: "metric.gmv", dependsOn: ["s1"] },
      ],
    },
    expect: [1002000000],
  },
  {
    id: "boolean-metric-misuse",
    title: "布尔判定列引用非 Rule 指标",
    path: "/api/v1/query/execute",
    body: {
      manifestId: MANIFEST_ID,
      mode: "LOOKUP",
      entityId: "concept.customer",
      attributes: ["property.customer.tier"],
      identity: { propertyId: "property.customer.customer_id", value: "c-1001" },
      booleanMetricId: "metric.gmv",
    },
    // 校验器在入口就判「不是 ruleId 型布尔指标」（DSL_INVALID）；规则未发布/表达式非法才是 RULE_* 系列
    expect: [1002000000, 1002000027],
  },
];

// 每轮用唯一的 request_id：request_id 是幂等键，复用会让上一轮的终态把请求挡在入口
const runId = `r${Date.now()}`;
const outcomes = [];
for (const probe of probes) {
  const response = await fetch(BASE + probe.path, {
    method: "POST",
    headers: { ...HEADERS, "X-Request-Id": `probe-${runId}-${probe.id}` },
    body: JSON.stringify(probe.body),
  });
  const httpStatus = response.status;
  let body;
  try {
    body = await response.json();
  } catch {
    body = { code: -1, msg: "<非 JSON 响应>" };
  }
  const code = body.code;
  const ok = httpStatus === 200 && probe.expect.includes(code);
  outcomes.push({
    id: probe.id,
    title: probe.title,
    path: probe.path,
    expectedCodes: probe.expect,
    actualCode: code,
    httpStatus,
    message: body.msg,
    ok,
  });
}

const failed = outcomes.filter((item) => !item.ok);
const evidence = {
  set: "m2-e5-adversarial",
  manifestId: MANIFEST_ID,
  ranAt: new Date().toISOString(),
  criteria: "LLM 无通道提交自由 SQL / 绝对日期（含未知字段拒绝）；全部被业务错误码拒绝，无 500",
  total: outcomes.length,
  passed: outcomes.length - failed.length,
  outcomes,
};
writeFileSync(EVIDENCE, JSON.stringify(evidence, null, 2) + "\n", "utf8");

console.log(`E5 对抗探针：${outcomes.length} 项，通过 ${outcomes.length - failed.length}`);
for (const item of outcomes) {
  console.log(`  ${item.ok ? "OK  " : "FAIL"} ${item.id} -> ${item.actualCode} ${item.message?.slice(0, 60) ?? ""}`);
}
if (failed.length > 0) {
  process.exitCode = 1;
}
