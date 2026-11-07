/**
 * 路径规划探针（M1/T4.4）：Golden Path 正反向样例（7 关系全覆盖）+ 负例。
 *
 * 走真实服务端点 GET /api/v1/ontology/paths（首发清单 2026.11.08-first 的本体快照）：
 *   正向/逆向：7 条关系 × 正反两向 → UNIQUE（并列在设计上不存在：golden 优先级可分辨）
 *   负例：未知业务对象、授权域排除（product 域剔除后 订单→商品 NONE）、跳数不足
 * 判据引用 v1.4 §7.3 第 8/9 条与 M1 规格 T4.4；本探针只输出实测。
 *
 * 运行：node scripts/poc/run-path-planning-probe.mjs [--base-url http://127.0.0.1:8080]
 * 产物：docs/06-verification/poc/路径规划探针-raw.json
 */
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const argOf = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};
const BASE = argOf("--base-url", "http://127.0.0.1:8080");
const OUTPUT = resolve(argOf("--output", "docs/06-verification/poc/路径规划探针-raw.json"));
const HEADERS = { "X-Ontos-Tenant": "demo", "X-Ontos-User": "demo" };

const RELATIONS = [
  { id: "ontology.Customer.placesOrder", from: "concept.customer", to: "concept.order" },
  { id: "ontology.Order.containsItem", from: "concept.order", to: "concept.order_item" },
  { id: "ontology.OrderItem.refersProduct", from: "concept.order_item", to: "concept.product" },
  { id: "ontology.Customer.performsEvent", from: "concept.customer", to: "concept.event" },
  { id: "ontology.Order.recordsEvent", from: "concept.order", to: "concept.event" },
  { id: "ontology.Event.attributedToChannel", from: "concept.event", to: "concept.channel" },
  { id: "ontology.Order.attributedToChannel", from: "concept.order", to: "concept.channel" },
];

async function plan(from, to, params = "") {
  const response = await fetch(
    `${BASE}/api/v1/ontology/paths?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}${params}`,
    { headers: HEADERS });
  return await response.json();
}

const results = [];
function record(kind, label, expect, data) {
  const status = data?.data?.status;
  const met = expect.includes(status);
  results.push({ kind, label, expect: expect.join("|"), status, hops: data?.data?.candidates?.[0]?.hops ?? null,
    candidateCount: data?.data?.candidates?.length ?? 0, met,
    reason: data?.data?.reason ?? null, golden: data?.data?.candidates?.[0]?.allGolden ?? null });
  console.log(`  [${met ? "PASS" : "FAIL"}] ${label} → ${status}`);
}

// 1) 七条关系正反向直达（全部应为 UNIQUE 单跳）
for (const relation of RELATIONS) {
  for (const [from, to, dir] of [[relation.from, relation.to, "正向"], [relation.to, relation.from, "逆向"]]) {
    const data = await plan(from, to);
    record("direct", `${relation.id}（${dir}）`, ["UNIQUE"], data);
  }
}

// 2) golden 优先级：客户→渠道 两条 2 跳路，golden（经订单）必须胜出
{
  const data = await plan("concept.customer", "concept.channel");
  const golden = data?.data?.candidates?.[0]?.allGolden === true
      && data?.data?.candidates?.[0]?.edges?.some(e => e.relationId === "ontology.Order.attributedToChannel");
  const status = data?.data?.status;
  const met = status === "UNIQUE" && golden;
  results.push({ kind: "golden-priority", label: "客户→渠道 golden 优先", expect: "UNIQUE+golden", status,
    met, candidateCount: data?.data?.candidates?.length ?? 0 });
  console.log(`  [${met ? "PASS" : "FAIL"}] 客户→渠道 golden 优先 → ${status}`);
}

// 3) 多跳组合：客户→商品（3 跳全 golden）
{
  const data = await plan("concept.customer", "concept.product");
  const candidate = data?.data?.candidates?.[0];
  const met = data?.data?.status === "UNIQUE" && candidate?.hops === 3 && candidate?.allGolden === true;
  results.push({ kind: "multi-hop", label: "客户→商品 3 跳全 golden", expect: "UNIQUE+3hops+golden",
    status: data?.data?.status, hops: candidate?.hops ?? null, met });
  console.log(`  [${met ? "PASS" : "FAIL"}] 客户→商品 3 跳 → ${data?.data?.status}（hops=${candidate?.hops}）`);
}

// 4) 负例：未知业务对象
{
  const data = await plan("concept.unknown", "concept.order");
  const met = data?.data?.status === "NONE";
  results.push({ kind: "negative", label: "未知业务对象", expect: "NONE", status: data?.data?.status, met });
  console.log(`  [${met ? "PASS" : "FAIL"}] 未知业务对象 → ${data?.data?.status}`);
}

// 5) 负例：授权域排除（剔除 product 域 → 订单→商品不可达）
{
  const data = await plan("concept.order", "concept.product", "&domains=trade,customer");
  const met = data?.data?.status === "NONE";
  results.push({ kind: "negative", label: "授权域排除 product", expect: "NONE", status: data?.data?.status, met,
    reason: data?.data?.reason });
  console.log(`  [${met ? "PASS" : "FAIL"}] 授权域排除 → ${data?.data?.status}`);
}

// 6) 负例：跳数不足（maxHops=2 时客户→商品 3 跳不可达）
{
  const data = await plan("concept.customer", "concept.product", "&maxHops=2");
  const met = data?.data?.status === "NONE";
  results.push({ kind: "negative", label: "跳数不足 maxHops=2", expect: "NONE", status: data?.data?.status, met });
  console.log(`  [${met ? "PASS" : "FAIL"}] 跳数不足 → ${data?.data?.status}`);
}

const passed = results.filter(r => r.met).length;
const summary = { generatedAt: new Date().toISOString(), baseUrl: BASE, total: results.length,
  passed, passRate: Number((passed / results.length).toFixed(4)), results };
writeFileSync(OUTPUT, JSON.stringify(summary, null, 2));
console.log(`结果：${passed}/${results.length} 通过`);
console.log(`产物：${OUTPUT}`);
if (passed !== results.length) process.exit(1);
