/**
 * 发布脚本（M1/T3.2）：一条命令从 Git 资产到清单生效。
 *
 * 流程（对齐技术选型 §4.2 与 M1 规格）：
 *   读取 ontos-assets/*.json → dry-run 预检（违规即中止）→ 确认发布 → 激活（可 --skip-activate）
 *
 * 资产文件为 JSON（与发布载荷同构，发布脚本零解析依赖）；YAML 表述待 CI 平台接入（M8）时评估，
 * 偏差已登记 M1 规格 §8 更正记录。CI 平台托管同样登记 M8，本脚本是本地等价流水线。
 *
 * 用法：
 *   node scripts/publish.mjs --manifest-id 2026.11.08-first \
 *        [--source-ref main@abc123] [--base-url http://127.0.0.1:8080] \
 *        [--assets ontos-assets/first-release-v22.json] [--skip-activate]
 *
 * B0 §3.3.3：发布端只收 v20 语义绑定形态（objectId + propertyId），默认资产指向三层化
 * v22 清单。显式 --assets 指向 v1–v19 历史资产会被预检的形态闸门拒绝；回滚请走
 * /manifests/{id}/activate 激活已有快照，不要重新发布旧形态。
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const argOf = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};
const flag = (name) => process.argv.includes(name);

const repo = resolve(fileURLToPath(new URL("..", import.meta.url)));
const BASE = argOf("--base-url", "http://127.0.0.1:8080");
const MANIFEST_ID = argOf("--manifest-id");
const SOURCE_REF = argOf("--source-ref", "manual");
const ASSETS = resolve(repo, argOf("--assets", "ontos-assets/first-release-v22.json"));
const SKIP_ACTIVATE = flag("--skip-activate");
const HEADERS = {
  "Content-Type": "application/json",
  "X-Ontos-Tenant": "demo",
  "X-Ontos-User": "publisher",
};

if (!MANIFEST_ID) {
  console.error("缺少 --manifest-id（清单不可变，新内容必须用新 ID）");
  process.exit(1);
}

const payload = JSON.parse(readFileSync(ASSETS, "utf8"));

async function post(path, body) {
  const response = await fetch(BASE + path, {
    method: "POST",
    headers: HEADERS,
    body: JSON.stringify(body ?? {}),
  });
  return await response.json();
}

console.log(`发布链路 → ${BASE}`);
console.log(`  资产: ${ASSETS}`);
console.log(`  清单: ${MANIFEST_ID}${SKIP_ACTIVATE ? "（发布后不激活）" : "（发布后激活）"}`);

// 1) dry-run 预检：违规即中止
const dryRun = await post("/api/v1/ontology/publish", {
  manifestId: MANIFEST_ID, sourceRef: SOURCE_REF, dryRun: true, payload,
});
if (dryRun.code !== 0) {
  console.error(`  预检请求失败: ${dryRun.msg}`);
  process.exit(1);
}
if (!dryRun.data.valid) {
  console.error("  预检未通过，中止发布：");
  for (const violation of dryRun.data.violations) console.error("    - " + violation);
  process.exit(1);
}
if (dryRun.data.alreadyExists) {
  console.error(`  manifest_id 已存在（清单不可变），换一个新 ID 再发布`);
  process.exit(1);
}
console.log(`  预检通过：${JSON.stringify(dryRun.data.assetCounts)}`);

// 2) 确认发布
const confirm = await post("/api/v1/ontology/publish", {
  manifestId: MANIFEST_ID, sourceRef: SOURCE_REF, dryRun: false, payload,
});
if (confirm.code !== 0) {
  console.error(`  发布失败: ${confirm.msg}`);
  process.exit(1);
}
console.log(`  已发布：content_hash = ${confirm.data.contentHash}`);

// 3) 激活（发布不自动生效，激活是显式运维动作）
if (!SKIP_ACTIVATE) {
  const activate = await post(`/api/v1/ontology/manifests/${MANIFEST_ID}/activate`);
  if (activate.code !== 0) {
    console.error(`  激活失败: ${activate.msg}`);
    process.exit(1);
  }
  console.log(`  已激活：${activate.data.manifestId}`);
}

console.log("完成。");
