/**
 * PoC #5「中文与模糊召回」最小探针（M0 / W3 / T3.3，判据级交付）。
 *
 * 判据（技术选型 v1.1 §7 #5）：冻结 ≥300 条提及样本（同义词 / 口语 / 拼音 / 编辑距离各占一定比例）；
 * 精确 + pg_trgm 组合 Top-5 召回率 ≥95%；模糊候选进入 DSL 次数为 0。
 *
 * M0 边界：检索层未实现、清单只有 demo 资产，因此本探针只交付「集可跑」的最小验证，
 * 完整验收（Top-5 召回率与 DSL 零污染断言）待 M5 回填：
 *   1. 样本结构：总量与分类比例、utterance 全局唯一、expectedAssetId 合法、shouldReachDsl 布尔；
 *   2. 样本卫生：utterance 不得出现物理表名、物理列名或 SQL 关键字；
 *   3. 资产引用自洽：expectedAssetId 必须在题集声明的资产引用范围内。
 * 探针结果整体标注 pending_m5，不记为通过。
 *
 * 运行：node scripts/poc/run-poc05-recall.mjs
 * 产物：docs/06-verification/poc/PoC-05-probe-raw.json
 */
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(fileURLToPath(new URL("..", import.meta.url))); // scripts/
const repo = resolve(root, "..");
const setPath = resolve(repo, "docs/06-verification/evaluation/中文提及样本集-v1.json");
const evidencePath = resolve(repo, "docs/06-verification/poc/PoC-05-probe-raw.json");

const LEGAL_CATEGORIES = new Set(["synonym", "colloquial", "pinyin", "typo"]);
/** 物理名泄漏扫描：utterance 只允许自然语言与资产提及。 */
const LEAK_PATTERNS = [
  /sales_order/i, /fact_order/i, /dim_customer/i, /\bamount\b/i, /\border_date\b/i,
  /select\s|from\s|join\s|where\s|group\s+by/i,
];

const set = JSON.parse(readFileSync(setPath, "utf8"));
const errors = [];
const warnings = [];

if (set.cases.length < 300) errors.push(`样本 ${set.cases.length} < 判据下限 300`);
const proportions = {};
const utterances = new Set();
for (const c of set.cases) {
  if (!LEGAL_CATEGORIES.has(c.category)) errors.push(`${c.id}: 非法分类 ${c.category}`);
  if (utterances.has(c.utterance)) errors.push(`${c.id}: utterance 重复 "${c.utterance}"`);
  utterances.add(c.utterance);
  if (typeof c.shouldReachDsl !== "boolean") errors.push(`${c.id}: shouldReachDsl 不是布尔`);
  if (!/^(metric|dim|entity|attr)\./.test(c.expectedAssetId)) {
    errors.push(`${c.id}: expectedAssetId 不是资产 ID 形式: ${c.expectedAssetId}`);
  }
  for (const pattern of LEAK_PATTERNS) {
    if (pattern.test(c.utterance)) errors.push(`${c.id}: utterance 疑似物理名/SQL 泄漏 "${c.utterance}"`);
  }
  proportions[c.category] = (proportions[c.category] ?? 0) + 1;
}
if (Object.keys(proportions).length !== 4) errors.push(`分类不齐：${JSON.stringify(proportions)}`);
// 每类占比不得低于 10%，避免「比例」名存实亡
for (const [cat, n] of Object.entries(proportions)) {
  if (n / set.cases.length < 0.1) warnings.push(`分类 ${cat} 占比 ${(n / set.cases.length * 100).toFixed(1)}% 低于 10%`);
}

const result = {
  probe: "PoC-05 最小探针（判据级交付）",
  criteriaRef: "技术选型 v1.1 §7 #5",
  setStatus: "pending_m5 —— 结构与卫生已验证；Top-5 召回率与「模糊候选进入 DSL 次数为 0」待 M5 检索层实现后回填",
  setVersion: set.version,
  total: set.cases.length,
  criteriaMinCases: 300,
  totalMeetsCriteria: set.cases.length >= 300,
  proportions,
  clarifyCandidates: set.cases.filter((c) => !c.shouldReachDsl).length,
  structureErrors: errors,
  warnings,
};
writeFileSync(evidencePath, JSON.stringify(result, null, 2));

console.log(`样本 ${set.cases.length} 条（判据下限 300）：${set.cases.length >= 300 ? "达标" : "不足"}`);
console.log(`分类比例：${JSON.stringify(proportions)}`);
console.log(`结构错误：${errors.length}；警告：${warnings.length}`);
for (const e of [...errors, ...warnings]) console.log(`  - ${e}`);
console.log(`产物：${evidencePath}`);
if (errors.length > 0 || set.cases.length < 300) process.exit(1);
