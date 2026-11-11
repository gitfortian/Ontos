#!/usr/bin/env node
/**
 * LLM 路径 vs 确定性降级路径对比（M7/W2/T2.3）。
 *
 * 输入两份**同一 test 集**的 runner 证据（`llm-test-runner.mjs` 产出）：
 *   --llm  真实模型路径（agent.enabled=true）
 *   --det  降级路径（agent.enabled=false，确定性提案器）
 *
 * 统计口径（对齐 M7 规格 R-5 与评测统计口径 v1，阈值引用 ADR-0005，不在脚本内改）：
 *   - 二元结果：同一题逐轮配对 → McNemar（b/c 为「LLM 过/降级不过」与反之）；
 *     p 值用 df=1 卡方近似（|b−c|−1）²/(b+c)，z=√χ² 后取正态尾概率。
 *   - 连续量（延迟、token）：配对 bootstrap（B=10000，95% 百分位区间）。
 * 说明：降级路径不产生模型调用，token 差值即 LLM 路径成本，不做「两侧都有」的伪配对。
 *
 * 用法：
 *   node scripts/verify/llm-vs-deterministic.mjs \
 *     --llm docs/06-verification/evaluation/LLM路径评测证据-v1.json \
 *     --det target/det-evidence.json \
 *     --out docs/06-verification/evaluation/LLM对比降级路径证据-v1.json
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const argOf = (name, fallback) => {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : fallback;
};
const LLM_PATH = resolve(repo, argOf("--llm",
  "docs/06-verification/evaluation/LLM路径评测证据-v1.json"));
const DET_PATH = resolve(repo, argOf("--det",
  "docs/06-verification/evaluation/降级路径评测证据-v1.json"));
const OUT = resolve(repo, argOf("--out",
  "docs/06-verification/evaluation/LLM对比降级路径证据-v1.json"));

const llm = JSON.parse(readFileSync(LLM_PATH, "utf8"));
const det = JSON.parse(readFileSync(DET_PATH, "utf8"));

/** 降级路径证据若只有 1 轮，则每个 LLM 轮次都与之配对（同题同会话语义不变）。 */
const detByCase = new Map();
for (const row of det.rows) {
  detByCase.set(row.caseId, row);
}

const pairs = [];
for (const row of llm.rows) {
  const other = detByCase.get(row.caseId);
  if (!other) {
    continue;
  }
  pairs.push({
    caseId: row.caseId,
    runIndex: row.runIndex,
    llmPass: row.pass,
    detPass: other.pass,
    llmLatency: row.latencyMs,
    detLatency: other.latencyMs,
    llmTokens: row.tokens,
    detTokens: 0,
    llmTerminal: row.terminal,
    detTerminal: other.terminal,
  });
}

const count = (predicate) => pairs.filter(predicate).length;
const bothPass = count((pair) => pair.llmPass && pair.detPass);
const llmOnly = count((pair) => pair.llmPass && !pair.detPass);
const detOnly = count((pair) => !pair.llmPass && pair.detPass);
const bothFail = count((pair) => !pair.llmPass && !pair.detPass);

/** McNemar（连续性校正）；b+c=0 时无差异证据，p=1。 */
function mcnemar(b, c) {
  if (b + c === 0) {
    return { chiSquare: 0, pValue: 1 };
  }
  const chiSquare = ((Math.abs(b - c) - 1) ** 2) / (b + c);
  // df=1 的卡方尾概率 = erfc(√(χ²/2))；用 Abramowitz-Stegun 7.1.26 近似 erf
  const x = Math.sqrt(chiSquare / 2);
  const t = 1 / (1 + 0.3275911 * x);
  const erf = 1 - ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736)
    * t + 0.254829592) * t * Math.exp(-x * x);
  return { chiSquare: Number(chiSquare.toFixed(4)), pValue: Number((1 - erf).toFixed(6)) };
}

const mcnemarResult = mcnemar(llmOnly, detOnly);

/** 配对 bootstrap（B=10000，95% 百分位区间）。 */
function pairedBootstrap(values, iterations = 10000) {
  if (values.length === 0) {
    return null;
  }
  const means = [];
  for (let index = 0; index < iterations; index += 1) {
    let sum = 0;
    for (let pick = 0; pick < values.length; pick += 1) {
      sum += values[Math.floor(Math.random() * values.length)];
    }
    means.push(sum / values.length);
  }
  means.sort((left, right) => left - right);
  const at = (p) => means[Math.min(means.length - 1, Math.floor((p / 100) * means.length))];
  return {
    mean: Number((values.reduce((sum, value) => sum + value, 0) / values.length).toFixed(2)),
    ci95: [Number(at(2.5).toFixed(2)), Number(at(97.5).toFixed(2))],
    samples: iterations,
  };
}

const latencyDelta = pairs.map((pair) => pair.llmLatency - pair.detLatency);
const tokenDelta = pairs.map((pair) => pair.llmTokens - pair.detTokens);

/** 分层对比（按 analysis_mode）。 */
const modes = new Map();
for (const pair of pairs) {
  const mode = llm.rows.find((row) => row.caseId === pair.caseId)?.layers?.analysis_mode ?? "unknown";
  if (!modes.has(mode)) {
    modes.set(mode, { total: 0, llmPassed: 0, detPassed: 0 });
  }
  const bucket = modes.get(mode);
  bucket.total += 1;
  bucket.llmPassed += pair.llmPass ? 1 : 0;
  bucket.detPassed += pair.detPass ? 1 : 0;
}

const evidence = {
  task: "M7/W2/T2.3",
  generatedAt: new Date().toISOString(),
  sources: {
    llm: { path: LLM_PATH.replace(`${repo}/`, ""), runId: llm.runId, runs: llm.runs, total: llm.summary.total },
    det: { path: DET_PATH.replace(`${repo}/`, ""), runId: det.runId, runs: det.runs, total: det.summary.total },
  },
  paired: {
    total: pairs.length,
    bothPass, llmOnly, detOnly, bothFail,
    llmRate: pairs.length ? Number((count((pair) => pair.llmPass) / pairs.length).toFixed(4)) : null,
    detRate: pairs.length ? Number((count((pair) => pair.detPass) / pairs.length).toFixed(4)) : null,
  },
  mcnemar: mcnemarResult,
  bootstrap: {
    latencyDeltaMs: pairedBootstrap(latencyDelta),
    tokenDelta: pairedBootstrap(tokenDelta),
  },
  byAnalysisMode: Object.fromEntries([...modes.entries()].map(([mode, bucket]) => [mode, {
    ...bucket,
    llmRate: Number((bucket.llmPassed / bucket.total).toFixed(4)),
    detRate: Number((bucket.detPassed / bucket.total).toFixed(4)),
  }])),
  notes: [
    "降级路径不调用模型，token 差值等于 LLM 路径的单题成本",
    "McNemar 用 df=1 卡方近似（连续性校正），p 值仅供差异方向判断，阈值不在本脚本内设定",
    "配对 bootstrap 以「同题同轮」为单位重采样，避免把不同题目的难度差异当成路径差异",
  ],
};
writeFileSync(OUT, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");

console.log(`配对样本 ${pairs.length}：both ${bothPass} / llm-only ${llmOnly} / det-only ${detOnly} / both-fail ${bothFail}`);
console.log(`McNemar χ²=${mcnemarResult.chiSquare} p=${mcnemarResult.pValue}`);
console.log(`延迟差 ${JSON.stringify(evidence.bootstrap.latencyDeltaMs)}`);
console.log(`token 差 ${JSON.stringify(evidence.bootstrap.tokenDelta)}`);
console.log(`→ ${OUT}`);
