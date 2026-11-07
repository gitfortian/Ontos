#!/usr/bin/env node
/**
 * 流式时序测量（PoC #10 补充判据）。
 *
 * 回答一个具体问题：**后端逐条 SSE 时，前端是不是真的逐字展示？**
 * 分两层测量，任一层断掉都会表现为「压缩批量返回」：
 *
 *   1. 网络层：在页面内 fetch + ReadableStream，记录每个 chunk 与 TEXT_MESSAGE_CONTENT 的到达时间，
 *      统计 delta 间隔与「同毫秒批量」（间隔 ≤1ms 视为一批）。
 *   2. 渲染层：点击真实「发送」按钮，走 agui-panel → streamAgui → setEvents → pre，
 *      按帧采样 pre 文本长度，统计更新次数、单次增量与间隔。
 *
 * 前置：
 *   - 前端 dev server 已启动（默认 http://127.0.0.1:5173），后端已启动（由 vite 代理 /agent）。
 *   - 需要 playwright-core 与 chromium。路径通过环境变量指定：
 *       PLAYWRIGHT_CORE=<playwright-core 的 index.js 绝对路径>
 *       CHROMIUM_PATH=<chrome.exe 绝对路径>
 *
 * 用法：
 *   node scripts/poc/measure-stream.mjs [--frontend-url http://127.0.0.1:5173]
 */
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const argOf = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};

const FRONTEND = argOf("--frontend-url", "http://127.0.0.1:5173");
const OUT_DIR = resolve(argOf("--output-dir", "docs/06-verification/poc"));
const PLAYWRIGHT_CORE = process.env.PLAYWRIGHT_CORE;
const CHROMIUM_PATH = process.env.CHROMIUM_PATH;

if (!PLAYWRIGHT_CORE || !CHROMIUM_PATH) {
  console.error("缺少环境变量：PLAYWRIGHT_CORE / CHROMIUM_PATH");
  process.exit(2);
}

const require = createRequire(import.meta.url);
const { chromium } = require(PLAYWRIGHT_CORE);

const QUESTION = "今年各区域的GMV是多少，按渠道拆分";

const stats = (values) => {
  if (values.length === 0) return { count: 0 };
  const sorted = [...values].sort((a, b) => a - b);
  return {
    count: values.length,
    min: sorted[0],
    p50: sorted[Math.floor(sorted.length * 0.5)],
    p90: sorted[Math.floor(sorted.length * 0.9)],
    max: sorted[sorted.length - 1],
    mean: Math.round(values.reduce((a, b) => a + b, 0) / values.length),
  };
};

const browser = await chromium.launch({
  executablePath: CHROMIUM_PATH,
  args: ["--no-sandbox", "--no-proxy-server"],
});
const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
await page.goto(`${FRONTEND}/ask`, { waitUntil: "domcontentloaded", timeout: 60000 });
await page.waitForSelector("textarea", { timeout: 30000 });

// ---------- 1. 网络层 ----------
const network = await page.evaluate(async (question) => {
  const t0 = performance.now();
  const frames = [];
  const res = await fetch("/agent/run/ontos", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    body: JSON.stringify({
      threadId: crypto.randomUUID(),
      runId: crypto.randomUUID(),
      messages: [{ id: crypto.randomUUID(), role: "user", content: question }],
      tools: [],
      context: [],
      state: {},
      forwardedProps: {},
    }),
  });
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let textLen = 0;
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    const at = Math.round(performance.now() - t0);
    const text = decoder.decode(value, { stream: true });
    frames.push({ kind: "chunk", t: at, bytes: text.length });
    buffer += text;
    const parts = buffer.split(/\r?\n\r?\n/);
    buffer = parts.pop() ?? "";
    for (const part of parts) {
      const line = part.split(/\r?\n/).find((l) => l.startsWith("data:"));
      if (!line) continue;
      try {
        const event = JSON.parse(line.slice(5).trim());
        if (event.type === "TEXT_MESSAGE_CONTENT") {
          textLen += (event.delta ?? "").length;
          frames.push({ kind: "delta", t: at, total: textLen });
        }
      } catch {
        /* 忽略非 JSON 帧 */
      }
    }
  }
  return { frames, status: res.status, totalMs: Math.round(performance.now() - t0) };
}, QUESTION);

const deltas = network.frames.filter((f) => f.kind === "delta");
const chunks = network.frames.filter((f) => f.kind === "chunk");
const deltaGaps = deltas.slice(1).map((d, i) => d.t - deltas[i].t);

let maxBatch = 1;
let burstCount = 0;
let run = 1;
for (const gap of deltaGaps) {
  if (gap <= 1) {
    run += 1;
    maxBatch = Math.max(maxBatch, run);
  } else {
    if (run > 1) burstCount += 1;
    run = 1;
  }
}
if (run > 1) burstCount += 1;

const networkReport = {
  generatedAt: new Date().toISOString(),
  layer: "network",
  question: QUESTION,
  transport: `fetch + ReadableStream via ${FRONTEND}/agent/run/ontos`,
  status: network.status,
  totalMs: network.totalMs,
  chunks: { count: chunks.length, gaps: stats(chunks.slice(1).map((c, i) => c.t - chunks[i].t)) },
  deltas: {
    count: deltas.length,
    totalChars: deltas.length ? deltas[deltas.length - 1].total : 0,
    gaps: stats(deltaGaps),
    maxSameTickBatch: maxBatch,
    burstCount,
  },
  samples: { first: deltas.slice(0, 12), last: deltas.slice(-6) },
};

// ---------- 2. 渲染层 ----------
await page.evaluate(() => {
  window.__samples = [];
  window.__t0 = 0;
  window.__install = () => {
    window.__t0 = performance.now();
    window.__samples = [];
    window.__timer = setInterval(() => {
      const pre = document.querySelector("pre");
      const len = pre ? (pre.textContent ?? "").length : 0;
      const last = window.__samples[window.__samples.length - 1];
      if (!last || last.len !== len) {
        window.__samples.push({ t: Math.round(performance.now() - window.__t0), len });
      }
    }, 16);
  };
});

await page.evaluate(() => window.__install());
await page.locator("textarea").first().fill(QUESTION);
await page.getByRole("button", { name: "发送" }).click();

const deadline = Date.now() + 90000;
while (Date.now() < deadline) {
  if ((await page.locator("text=已完成").count()) > 0) break;
  await page.waitForTimeout(400);
}
await page.waitForTimeout(600);

const render = await page.evaluate(() => {
  clearInterval(window.__timer);
  return { samples: window.__samples, totalMs: Math.round(performance.now() - window.__t0) };
});

await browser.close();

const samples = render.samples.filter((s) => s.len > 0);
const lengths = samples.map((s) => s.len);
const increments = lengths.slice(1).map((len, i) => len - lengths[i]).filter((d) => d > 0);
const renderGaps = samples.slice(1).map((s, i) => s.t - samples[i].t);

const renderReport = {
  generatedAt: new Date().toISOString(),
  layer: "render",
  question: QUESTION,
  path: "真实组件：点击发送 → agui-panel → streamAgui → setEvents → pre",
  totalMs: render.totalMs,
  renderUpdates: samples.length,
  finalChars: lengths[lengths.length - 1] ?? 0,
  charIncrements: stats(increments),
  updateGaps: stats(renderGaps),
  bigJumpsOver40: increments.filter((d) => d > 40).length,
  samples: { first: samples.slice(0, 15), last: samples.slice(-8) },
};

mkdirSync(dirname(resolve(OUT_DIR, "x")), { recursive: true });
writeFileSync(resolve(OUT_DIR, "PoC-10-流式时序-raw.json"), `${JSON.stringify(networkReport, null, 2)}\n`, "utf8");
writeFileSync(resolve(OUT_DIR, "PoC-10-流式渲染-raw.json"), `${JSON.stringify(renderReport, null, 2)}\n`, "utf8");

const verdict =
  networkReport.deltas.maxSameTickBatch <= 1 && renderReport.bigJumpsOver40 === 0
    ? "PASS（逐条到达、逐次渲染，无攒批）"
    : "FAIL（存在攒批，按 §8.5 顺序排查）";

console.log("=== 流式时序 ===");
console.log(`网络层：${chunks.length} chunk / ${deltas.length} delta（${networkReport.deltas.totalChars} 字符）`);
console.log(`  delta 间隔 ${JSON.stringify(networkReport.deltas.gaps)}`);
console.log(`  同毫秒最大批量 ${maxBatch} 个，${burstCount} 批`);
console.log(`渲染层：${samples.length} 次更新，每次增量 ${JSON.stringify(renderReport.charIncrements)}`);
console.log(`  更新间隔 ${JSON.stringify(renderReport.updateGaps)}`);
console.log(`  >40 字符跳变 ${renderReport.bigJumpsOver40} 次`);
console.log(`结论：${verdict}`);
console.log(`原始输出：${OUT_DIR}/PoC-10-流式时序-raw.json、PoC-10-流式渲染-raw.json`);
