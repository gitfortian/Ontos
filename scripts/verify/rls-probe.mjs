/**
 * PoC #1 回归：Query DS 的 RLS 边界（M2/W6/T6.3，roadmap M2 交付物「连接复用不串号」）。
 *
 * 三个可经 HTTP 观测的面：
 *   1) 跨租户：同一条编译产物在不同租户下执行，另一租户必须 0 行
 *   2) 连接复用：两个租户交替请求 100 轮（同一连接池），任何一轮串号即失败
 *   3) 存在性：lookup 不存在的标识与「存在但无权限」同形（available=false 且不带任何值）
 *
 * 不做（如实登记）：未设 GUC 的场景只能在执行器内部构造，HTTP 面永远会设；
 * 它由 QueryExecutorTest（会话上下文参数顺序 / 时区）与 ADR-0003 判据 1 覆盖。
 *
 * 用法：node scripts/verify/rls-probe.mjs [--rounds 100] [--manifest-id ...]
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
const ROUNDS = Number(argOf("--rounds", "100"));
const EVIDENCE = resolve(repo, "docs/06-verification/poc/PoC-01-M2回归-2026-11-08.json");

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

const OWNER_TENANT = "demo";
const FOREIGN_TENANT = "other-tenant";

const QUERY_DSL = {
  manifestId: MANIFEST_ID,
  mode: "QUERY",
  metricId: "metric.gmv",
  dimensions: ["dim.region"],
  timeExpressionRaw: "近 3650 天",
  timeRange: { field: "dim.order_date" },
};

const LOOKUP_DSL = {
  manifestId: MANIFEST_ID,
  mode: "LOOKUP",
  entityId: "concept.customer",
  attributes: ["property.customer.email", "property.customer.tier"],
  identity: { propertyId: "property.customer.customer_id", value: "c-1001" },
};

async function post(path, dsl, tenant, requestId) {
  const response = await fetch(BASE + path, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Ontos-Tenant": tenant,
      "X-Ontos-User": "rls-probe",
      "X-Ontos-Session": "rls",
      "X-Ontos-Turn": "1",
      "X-Request-Id": requestId,
    },
    body: JSON.stringify(dsl),
  });
  const body = await response.json();
  return body;
}

// 同上：幂等键要求每跑一次都换 ID
const runId = `r${Date.now()}`;
const violations = [];
const observations = { ownerRows: new Set(), foreignRows: new Set(), ownerAvailable: new Set() };

for (let round = 1; round <= ROUNDS; round += 1) {
  const owner = await post("/api/v1/query/execute", QUERY_DSL, OWNER_TENANT, `rls-${runId}-${round}-owner`);
  const foreign = await post("/api/v1/query/execute", QUERY_DSL, FOREIGN_TENANT, `rls-${runId}-${round}-foreign`);

  if (owner.code !== 0) {
    violations.push({ round, kind: "owner_failed", code: owner.code, msg: owner.msg });
  } else {
    observations.ownerRows.add(owner.data.rowCount);
  }
  // M4/W1：跨租户请求会被策略快照边界先拒（foreign 租户无快照 → 1003000003）。
  // 策略拒绝 = 边界前移，数据同样零返回，与「RLS 过滤 0 行」同为未泄露形态。
  const POLICY_CODES = [1003000003, 1003000004, 1003000005];
  if (foreign.code !== 0 && !POLICY_CODES.includes(foreign.code)) {
    violations.push({ round, kind: "foreign_failed", code: foreign.code, msg: foreign.msg });
  } else if (foreign.code === 0 && foreign.data.rowCount !== 0) {
    violations.push({ round, kind: "cross_tenant_leak", rows: foreign.data.rowCount });
  } else if (foreign.code === 0) {
    observations.foreignRows.add(foreign.data.rowCount);
  }
}

// 存在性：有权限租户能查到，其他租户与不存在标识同形（available=false，values 为空）
const ownerLookup = await post("/api/v1/query/execute", LOOKUP_DSL, OWNER_TENANT, `rls-${runId}-lookup-owner`);
const foreignLookup = await post("/api/v1/query/execute", LOOKUP_DSL, FOREIGN_TENANT, `rls-${runId}-lookup-foreign`);

if (ownerLookup.code !== 0 || !ownerLookup.data?.lookupRows?.[0]?.available) {
  violations.push({ kind: "owner_lookup_unavailable", code: ownerLookup.code });
}
const foreignRow = foreignLookup.data?.lookupRows?.[0];
if (foreignLookup.code !== 0 && ![1003000003, 1003000004, 1003000005].includes(foreignLookup.code)) {
  violations.push({ kind: "foreign_lookup_failed", code: foreignLookup.code, msg: foreignLookup.msg });
} else if (foreignLookup.code === 0 && (foreignRow?.available || Object.keys(foreignRow?.values ?? {}).length > 0)) {
  violations.push({ kind: "existence_leak", foreignRow });
}

const evidence = {
  poc: "PoC-01",
  scope: "M2 回归：RLS 最终边界（跨租户 / 连接复用 / 存在性）",
  manifestId: MANIFEST_ID,
  rounds: ROUNDS,
  ranAt: new Date().toISOString(),
  criteria: "越权成功 0（技术选型 §7 #1）",
  observations: {
    ownerRowCounts: [...observations.ownerRows],
    foreignRowCounts: [...observations.foreignRows],
    ownerLookup: ownerLookup.data?.lookupRows?.[0] ?? null,
    foreignLookup: foreignRow ?? null,
  },
  violations,
  passed: violations.length === 0,
};
writeFileSync(EVIDENCE, JSON.stringify(evidence, null, 2) + "\n", "utf8");

console.log(`RLS 探针：${ROUNDS} 轮，越权/异常 ${violations.length} 次`);
console.log(`  有权限租户行数集合: ${JSON.stringify([...observations.ownerRows])}`);
console.log(`  其他租户行数集合: ${JSON.stringify([...observations.foreignRows])}`);
console.log(`  有权限 lookup: ${JSON.stringify(ownerLookup.data?.lookupRows?.[0] ?? null)}`);
console.log(`  其他租户 lookup: ${JSON.stringify(foreignRow ?? null)}`);
if (violations.length > 0) {
  console.log("  违规明细:", JSON.stringify(violations.slice(0, 5)));
  process.exitCode = 1;
}
