/**
 * PoC #7 前置回归：跨轮敏感累计与差分即时动作（M4/W2，规格 v1.1 §3.2 + ADR-0002）。
 *
 * 前置：server 以 local profile + `--ontos.policy.dev-allow-all=false` 启动（强制快照判定），
 *       V11 迁移已应用；本脚本开头清空 sensitive_query_event 与本次产生的审批单。
 *
 * 阶段（demo 租户，pii 资产 = property.customer.email）：
 *   T1 个体探针：单行 email lookup（rowCount=1）→ 即时转审批（individual_probe）
 *   T2 批量记账：多行 lookup 连续查询 → 记账不触发（plain）
 *   T3 精确差分：identities 集合差恰 1 + 基数差恰 1 + 均>1 → 即时转审批（differential_instant）
 *   T4 窗口触发：session 级 pii 计数达 T(8) → SENSITIVE_QUERY_SEQUENCE（第 8 次起）
 *
 * 用法：node scripts/verify/sensitive-probe.mjs [--base-url http://127.0.0.1:8080]
 * 证据：docs/06-verification/poc/PoC-07-M4W2回归-<日期>.json
 */
import { writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const argOf = (name, fallback) => {
  const inline = process.argv.find((item) => item.startsWith(`${name}=`));
  if (inline) return inline.slice(name.length + 1);
  const index = process.argv.indexOf(name);
  return index > -1 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
};

const repo = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const BASE = argOf("--base-url", "http://127.0.0.1:8080");
const PSQL = argOf("--psql", "D:/pg/bin/psql.exe");
const TODAY = new Date().toISOString().slice(0, 10);
const EVIDENCE = resolve(repo, `docs/06-verification/poc/PoC-07-M4W2回归-${TODAY}.json`);

const results = [];
const note = (phase, name, pass, detail) => {
  results.push({ phase, name, pass, detail });
  console.log(`${pass ? "PASS" : "FAIL"} [${phase}] ${name}${detail ? " — " + JSON.stringify(detail) : ""}`);
};

const sql = (query) => execFileSync(PSQL,
  ["-U", "postgres", "-h", "localhost", "-d", "ontos", "-t", "-A", "-c", query],
  { env: { ...process.env, PGPASSWORD: "123456" } }).toString().trim();

const headers = (tenant, user, session, turn) => ({
  "Content-Type": "application/json", "X-Ontos-Tenant": tenant,
  "X-Ontos-User": user, "X-Ontos-Session": session, "X-Ontos-Turn": String(turn),
});

const post = async (path, body, tenant, user, session, turn) => {
  const response = await fetch(BASE + path, {
    method: "POST", headers: headers(tenant, user, session, turn), body: JSON.stringify(body),
  });
  return response.json();
};

// ---------- 前置：清单 + 清库 ----------
const manifestId = await fetch(BASE + "/api/v1/ontology/manifests/current", {
  headers: headers("demo", "verify-runner", "verify", 1),
}).then((r) => r.json()).then((b) => b?.data?.manifestId);
if (!manifestId) {
  console.error("无法取得当前生效清单");
  process.exit(1);
}
console.log(`当前清单: ${manifestId}`);

sql("DELETE FROM sensitive_query_event WHERE tenant_id = 'demo'");
sql("DELETE FROM approval_request WHERE tenant_id = 'demo' AND kind = 'SENSITIVE_SEQUENCE'");
sql("DELETE FROM query_cache");
console.log("已清空 sensitive_query_event / SENSITIVE_SEQUENCE 审批单 / query_cache");

const eventCount = () => Number(sql("SELECT count(*) FROM sensitive_query_event WHERE tenant_id = 'demo'"));
const approvalCount = () => Number(sql(
  "SELECT count(*) FROM approval_request WHERE tenant_id = 'demo' AND kind = 'SENSITIVE_SEQUENCE'"));
const latestTag = () => sql(
  "SELECT pattern_tag FROM sensitive_query_event WHERE tenant_id = 'demo'"
  + " ORDER BY occurred_at DESC, id DESC LIMIT 1");

// 每类场景独立 user：窗口计数按 (tenant,user,session,key) 取数，互不污染；
// existence 层 T=4 比 pii T=8 更严，同 user 连续跑四类会被 existence 提前拦截
const lookup = (attributes, identity, requestId) => post("/api/v1/query/execute", {
  manifestId, mode: "LOOKUP", entityId: "concept.customer",
  attributes, identity,
}, "demo", "probe-individual", "probe-session", requestId);

const batch = (identities, requestId, user = "probe-window") => post("/api/v1/query/execute", {
  manifestId, mode: "LOOKUP_BATCH", entityId: "concept.customer",
  identityPropertyId: "property.customer.customer_id",
  attributes: ["property.customer.email"], identities,
}, "demo", user, "probe-session", requestId);

const SEQ_CODE = 1003000008;

// ---------- T1 个体探针 ----------
// email 兼 pii + existence（双源叠加）：每个请求按适用层各记一行（pii 行 + existence 行）
const t1 = await lookup(["property.customer.email"],
  { propertyId: "property.customer.customer_id", value: "c-1001" }, 1);
note("T1", "个体探针：单行 pii lookup 即时转审批",
  t1?.code === SEQ_CODE,
  { code: t1?.code, message: t1?.message?.slice(0, 60) });
note("T1", "个体探针审批单已建", approvalCount() === 1, { approvals: approvalCount() });
note("T1", "记账 pattern_tag=individual_probe（pii 行）",
  latestTag() === "individual_probe", { tag: latestTag() });
note("T1", "双源叠加：同时记 existence 行（plain）",
  eventCount() === 2, { events: eventCount() });

// ---------- T3 精确差分（必须在 existence 层 T=4 触发拒答前完成） ----------
const t3Prev = await batch(["c-1001", "c-1002", "c-1003"], 4, "probe-diff");
const t3Cur = await batch(["c-1001", "c-1002"], 5, "probe-diff");
note("T3", "差分前件正常返回（rowCount=3 > 1）", t3Prev?.code === 0, { code: t3Prev?.code });
note("T3", "差分后件即时转审批（集合差1+基数差1+均>1）",
  t3Cur?.code === SEQ_CODE, { code: t3Cur?.code });
note("T3", "差分记账 pattern_tag=differential_instant",
  latestTag() === "differential_instant", { tag: latestTag() });
// 此时 pii 层计数 = 3（个体1 + prev1 + cur1）、existence 层 = 3，均在阈值下

// ---------- T2 批量记账（不同组合，避开缓存命中） ----------
const t2a = await batch(["c-1001", "c-1002"], 2, "probe-batch");
const t2b = await batch(["c-1002", "c-1003"], 3, "probe-batch");
// 个体 2 + 差分 4 + 批量 4 = 10 行（分段 user 后各窗独立计数，批量段 2 次均不触发）
const afterT2 = eventCount();
note("T2", "每请求按适用层各记一行（个体2+差分4+批量4 = 10 行）",
  afterT2 === 10, { events: afterT2 });
note("T2", "批量段独立 user 未触发（existence 计数 2 < T4）",
  t2a?.code === 0 && t2b?.code === 0, { a: t2a?.code, b: t2b?.code });

// ---------- T4 窗口触发 ----------
// probe-window 独立窗口：4 次不同参批量，existence 层计数 1→2→3→4（T=4）→ 拒答档触发；
// pii 层同到 4 < 8（转审批档未达）——两层独立判定，取档位较高者
const t4a = await batch(["c-1001", "c-1002"], 6);
const t4b = await batch(["c-1002", "c-1003"], 7);
const t4c = await batch(["c-1001", "c-1003"], 8);
const t4d = await batch(["c-1001", "c-1002", "c-1003"], 9);
note("T4", "阈值前正常放行（existence 计数 3 < 4）",
  t4a?.code === 0 && t4b?.code === 0 && t4c?.code === 0,
  { a: t4a?.code, b: t4b?.code, c: t4c?.code });
note("T4", "session 级计数达 T(4) 触发拒答（existence 层 T=4 拒答档）",
  t4d?.code === SEQ_CODE, { code: t4d?.code, events: eventCount() });
note("T4", "触发后窗口持续拒答——含缓存命中路径（临时冻结语义）",
  (await batch(["c-1001", "c-1003"], 10))?.code === SEQ_CODE,
  { code: (await batch(["c-1002", "c-1003"], 11))?.code });
note("T4", "审批单累计 ≥ 3（个体 + 差分 + 窗口）", approvalCount() >= 3, { approvals: approvalCount() });
const blockTagged = sql(
  "SELECT count(*) FROM approval_request WHERE tenant_id='demo' AND kind='SENSITIVE_SEQUENCE'"
  + " AND reason = 'SENSITIVE_SEQUENCE_BLOCKED'");
note("T4", "窗口拒答档审批单 reason=SENSITIVE_SEQUENCE_BLOCKED",
  Number(blockTagged) >= 1, { blocked: Number(blockTagged) });

// ---------- T5 审批放行令牌（T2.3，规格 §3.3） ----------
// 独立 user：个体探针触发 → 自审批拒绝 → 正确决策 APPROVE → 令牌单次放行 → 复用拒绝
const grantLookup = (identity, requestId, user, approvalId) => post("/api/v1/query/execute", {
  manifestId, mode: "LOOKUP", entityId: "concept.customer",
  attributes: ["property.customer.email"], identity,
}, "demo", user, "probe-session", requestId, approvalId);

// post 需要支持审批头：重新定义带 approval 的请求函数
const postWithApproval = async (path, body, tenant, user, session, turn, approvalId) => {
  const h = headers(tenant, user, session, turn);
  if (approvalId) h["X-Ontos-Approval"] = approvalId;
  const response = await fetch(BASE + path, {
    method: "POST", headers: h, body: JSON.stringify(body),
  });
  return response.json();
};
const grantLookup2 = (identity, requestId, user, approvalId) => postWithApproval(
  "/api/v1/query/execute", {
    manifestId, mode: "LOOKUP", entityId: "concept.customer",
    attributes: ["property.customer.email"], identity,
  }, "demo", user, "probe-session", requestId, approvalId);
const decideApproval = async (approvalId, decision, user) => postWithApproval(
  `/api/v1/approvals/${approvalId}/decide`, { decision }, "demo", user, "probe-session", 1);

// 5.1 个体探针触发（applicant = probe-grant）
const t5Trigger = await grantLookup2(
  { propertyId: "property.customer.customer_id", value: "c-1001" }, 20, "probe-grant");
const triggerText = t5Trigger?.msg ?? t5Trigger?.message ?? '';
const approvalId = (triggerText.match(/审批单:?\s*([0-9a-f-]{36})/) || [])[1];
note("T5", "触发返回审批单 ID", Boolean(approvalId),
  { code: t5Trigger?.code, approvalId });

// 5.2 自审批禁止：申请人自己批准 → 1003000010
const selfDecision = await decideApproval(approvalId, "APPROVED", "probe-grant");
note("T5", "自审批禁止（决策人 ≠ 申请人）", selfDecision?.code === 1003000010,
  { code: selfDecision?.code });

// 5.3 正确决策人批准 → APPROVED + 指纹生成
const approved = await decideApproval(approvalId, "APPROVED", "approver-main");
note("T5", "审批人批准通过", approved?.code === 0, { code: approved?.code });
const grantRow = sql(`SELECT status, grant_fingerprint IS NOT NULL, grant_consumed_at IS NULL
  FROM approval_request WHERE id = '${approvalId}'`);
note("T5", "APPROVED 单已生成放行指纹（未消费）",
  grantRow === "APPROVED|t|t", { row: grantRow });

// 5.4 携令牌重放同一查询 → 指纹匹配 → 单次放行
const granted = await grantLookup2(
  { propertyId: "property.customer.customer_id", value: "c-1001" }, 21, "probe-grant", approvalId);
note("T5", "令牌放行：同指纹单次执行成功", granted?.code === 0, { code: granted?.code });

// 5.5 令牌复用 → 已消费 → 拒绝
const reused = await grantLookup2(
  { propertyId: "property.customer.customer_id", value: "c-1001" }, 22, "probe-grant", approvalId);
note("T5", "令牌单次有效：复用被拒", reused?.code === SEQ_CODE, { code: reused?.code });

// 5.6 换 filters（不同身份）→ 新触发 → 旧令牌指纹不符不放行（直接无令牌即可，另验指纹）
const otherTrigger = await grantLookup2(
  { propertyId: "property.customer.customer_id", value: "c-1003" }, 23, "probe-grant");
note("T5", "换查询（不同 filters）重新触发审批", otherTrigger?.code === SEQ_CODE,
  { code: otherTrigger?.code });

// ---------- T6 审计回放与恰好过期（W3/T3.2 + T3.1） ----------
// 6.1 审计回放：触发请求的审计行关联审批单 + 计数器哈希增量，与事件行同值同源
const triggeredRequestId = sql(
  "SELECT request_id FROM sensitive_query_event WHERE tenant_id='demo' AND pattern_tag='individual_probe'"
  + " ORDER BY id DESC LIMIT 1");
const auditRow = sql(
  "SELECT approval_id IS NOT NULL, sensitive_counters_delta IS NOT NULL"
  + " FROM audit_log WHERE tenant_id='demo' AND request_id='" + triggeredRequestId + "'"
  + " AND reason_code LIKE 'SENSITIVE%' LIMIT 1");
note("T6", "触发审计行关联 approval_id + counters_delta", auditRow === "t|t", { row: auditRow });
const eventDelta = sql(
  "SELECT context_delta FROM sensitive_query_event WHERE request_id='" + triggeredRequestId + "'"
  + " AND context_delta IS NOT NULL LIMIT 1");
const auditDelta = sql(
  "SELECT sensitive_counters_delta FROM audit_log WHERE request_id='" + triggeredRequestId + "'"
  + " AND sensitive_counters_delta IS NOT NULL LIMIT 1");
note("T6", "事件行 context_delta 与审计行同值同源",
  eventDelta.length === 64 && eventDelta === auditDelta,
  { eventDelta: eventDelta.slice(0, 12), auditDelta: auditDelta.slice(0, 12) });

// 6.2 恰好过期边界（T3.1，`<=`）：requested_at = now()-TTL 置 EXPIRED；差 1 秒未过期保留 PENDING
sql("DELETE FROM approval_request WHERE user_id LIKE 'boundary-%'");
sql("INSERT INTO approval_request (tenant_id, user_id, kind, payload, requested_at)"
  + " VALUES ('demo','boundary-exact','SENSITIVE_SEQUENCE','{}'::jsonb,"
  + " now() - interval '15 minutes')");
sql("INSERT INTO approval_request (tenant_id, user_id, kind, payload, requested_at)"
  + " VALUES ('demo','boundary-fresh','SENSITIVE_SEQUENCE','{}'::jsonb,"
  + " now() - interval '14 minutes 59 seconds')");
sql("UPDATE approval_request SET status='PENDING' WHERE user_id LIKE 'boundary-%'");
// 惰性过期经服务端点触发（page 是 GET，前置 expireStale）
await fetch(BASE + "/api/v1/approvals?pageNo=1&pageSize=1", {
  headers: headers("demo", "approver-main", "probe-session", 1),
});
const exactStatus = sql("SELECT status FROM approval_request WHERE user_id='boundary-exact'");
const freshStatus = sql("SELECT status FROM approval_request WHERE user_id='boundary-fresh'");
note("T6", "恰好等于 TTL(15m) 的 PENDING 单被置 EXPIRED", exactStatus === "EXPIRED",
  { exactStatus });
note("T6", "TTL 前 1 秒的单据保留 PENDING", freshStatus === "PENDING", { freshStatus });
sql("DELETE FROM approval_request WHERE user_id LIKE 'boundary-%'");

// ---------- 汇总 ----------
const failures = results.filter((item) => !item.pass);
const evidence = {
  generatedAt: new Date().toISOString(),
  manifestId,
  devAllowAll: false,
  total: results.length, failures: failures.length,
  results,
};
writeFileSync(EVIDENCE, JSON.stringify(evidence, null, 2));
console.log(`\n证据: ${EVIDENCE}`);
console.log(`总计 ${results.length} 项，失败 ${failures.length} 项`);
process.exit(failures.length === 0 ? 0 : 1);
