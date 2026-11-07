/**
 * PoC #6「pgvector HNSW」最小探针（M0 / W3 / T3.3，判据级交付）。
 *
 * 判据（技术选型 v1.1 §7 #6）：仅在 M5 配对评测证明召回收益后启动；
 * 版本 ≥0.8.4（目标 0.8.6），低于 0.8.3 禁止用于生产 HNSW。
 *
 * M0 边界：pgvector 明确「不启用」（M5 条件触发），本探针只交付「版本可探测」：
 * 读取 pg_available_extensions / pg_extension 中的 vector 版本并与生产下限比对，
 * 产出供 M5 决策的基线读数。探针结果整体标注 pending_m5，不记为通过。
 *
 * 运行：node scripts/poc/run-poc06-pgvector.mjs
 * 产物：docs/06-verification/poc/PoC-06-probe-raw.json
 */
import { execFileSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(fileURLToPath(new URL("..", import.meta.url)), "..");
const evidencePath = resolve(repo, "docs/06-verification/poc/PoC-06-probe-raw.json");

const PSQL_BIN = process.env.PSQL_BIN ?? "D:/pg/bin/psql.exe";
const PROD_MIN_VERSION = "0.8.4"; // 与判据一致的生产 HNSW 下限；低于 0.8.3 一律禁止

function psql(sql) {
  return execFileSync(PSQL_BIN, [
    "-h", "127.0.0.1", "-p", "5432", "-U", "postgres", "-d", "ontos_queryds",
    "-t", "-A", "-v", "ON_ERROR_STOP=1", "-c", sql,
  ], { env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD ?? "123456" }, encoding: "utf8" }).trim();
}

function compareVersion(a, b) {
  const pa = a.split(".").map(Number);
  const pb = b.split(".").map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

const result = {
  probe: "PoC-06 最小探针（判据级交付）",
  criteriaRef: "技术选型 v1.1 §7 #6",
  setStatus: "pending_m5 —— 版本可探测已验证；是否启用由 M5 配对评测收益决定，探针不给出启用结论",
  prodMinVersion: PROD_MIN_VERSION,
};

try {
  const available = psql(
    "SELECT coalesce(max(default_version), 'none') FROM pg_available_extensions WHERE name = 'vector'");
  const installed = psql(
    "SELECT coalesce(max(extversion), 'none') FROM pg_extension WHERE extname = 'vector'");
  result.availableVersion = available;
  result.installedVersion = installed;
  result.versionProbeStatus = available === "none" ? "extension_unavailable" : "version_readable";
  if (available !== "none") {
    result.meetsProdMin = compareVersion(available, PROD_MIN_VERSION) >= 0;
    result.versionGate = result.meetsProdMin
      ? `可用版本 ${available} ≥ 生产下限 ${PROD_MIN_VERSION}`
      : `可用版本 ${available} 低于生产下限 ${PROD_MIN_VERSION}（启用前必须解决）`;
  }
  result.enabled = installed !== "none";
  result.note = result.enabled
    ? "pgvector 已启用，但启用决策仍以 M5 配对评测为准"
    : "pgvector 未启用，符合「M5 条件触发」的当前预期";
} catch (err) {
  result.probeStatus = "error";
  result.error = String(err).split("\n")[0];
}

writeFileSync(evidencePath, JSON.stringify(result, null, 2));
console.log(JSON.stringify(result, null, 2));
console.log(`产物：${evidencePath}`);
if (result.probeStatus === "error") process.exit(1);
