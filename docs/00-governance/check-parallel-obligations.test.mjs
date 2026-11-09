/**
 * check-parallel-obligations.mjs 的反例测试：纪律检查本身必须有效。
 * 运行：node --test docs/00-governance/check-parallel-obligations.test.mjs
 * 每个用例在临时目录构造最小仓库，不触碰真实 docs/。
 */
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";
import { evaluate } from "./check-parallel-obligations.mjs";

const ROADMAP = "docs/03-plans/roadmap/Ontos开发计划-阶段性功能拆解-v1.md";
const LEDGER = "docs/00-governance/parallel-obligations.json";
const CHECKLISTS = "docs/06-verification/checklists";
const PROGRESS = "docs/05-progress/2026";
const ADR = "docs/01-decisions/ADR-0014-并行义务纪律与M7同步建设偏离.md";
const SNAPSHOT = `${PROGRESS}/2026-11-09.md`;
const REPORTS = [
  "M0-验收报告-v2.md",
  "M1-验收报告-v2.md",
  "M2-验收报告-v1.md",
  "M3-验收报告-v1.md",
];

const ROADMAP_TEXT = [
  "# 测试计划",
  "## 1. 阶段总览",
  "| 阶段 | 功能域 | 目标 | 依赖 | 状态 |",
  "|---|---|---|---|---|",
  "| **M0** | 基线 | x | — | **已通过** |",
  "| **M1** | 本体 | x | M0 | **已通过** |",
  "| **M2** | 编译 | x | M1 | **已通过** |",
  "| **M3** | 闭环 | x | M2 | **已通过** |",
  "| **M4** | 安全 | x | 可与 M1–M3 并行 | 待启动 |",
  "| **M5** | 会话 | x | M3 + M4 | 待启动 |",
  "| **M6** | 分析 | x | M5 | 待启动 |",
  "| **M7** | 评测 | x | 自 M1 起同步建设，M6 后收敛 | 待启动 |",
  "| **M8** | 上线 | x | M7 | 待启动 |",
  "**主关键路径**：`M0 → M1 → M2 → M3 → M5 → M6 → M7 → M8`",
  "**并行支线**：M4 与 M1–M3 并行；M7 的评测框架从 M1 起同步建设（不允许最后补）。",
  "---",
  "## 4. 闸门与并行原则",
  "3. **安全与评测不许后置**：M4 与 M1–M3 并行，M7 从 M1 起同步建设。",
].join("\n");

const DEFAULT_PROGRESS = [
  "# 进度快照",
  "## 并行义务",
  "- OBL-M4 deferred，触发 M5",
  "- OBL-M7 deferred，触发 M4",
].join("\n");

function baseLedger() {
  return {
    schemaVersion: 1,
    updatedAt: "2026-11-09",
    source: ROADMAP,
    obligations: [
      {
        id: "OBL-M4",
        stage: "M4",
        clause: "与 M1–M3 并行",
        startStage: "M1",
        status: "deferred",
        triggerStage: "M5",
        owner: "项目负责人",
        reason: "M1–M3 已通过，M4 待启动",
        evidence: [ADR, SNAPSHOT],
      },
      {
        id: "OBL-M7",
        stage: "M7",
        clause: "自 M1 起同步建设",
        startStage: "M1",
        status: "deferred",
        triggerStage: "M4",
        owner: "项目负责人",
        reason: "M1–M3 已通过，M7 待启动",
        evidence: [ADR, SNAPSHOT],
      },
    ],
  };
}

function fixture(t, options = {}) {
  const root = mkdtempSync(join(tmpdir(), "ontos-obligations-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const put = (path, content) => {
    const target = join(root, path);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, content, "utf8");
  };
  put(ROADMAP, options.roadmap ?? ROADMAP_TEXT);
  put(LEDGER, JSON.stringify(options.ledger ?? baseLedger(), null, 2));
  put(ADR, options.adr ?? "# ADR-0014\nM4 与 M7 两条并行义务的偏离处置。\n");
  put(SNAPSHOT, options.progress ?? DEFAULT_PROGRESS);
  for (const [path, content] of options.extraProgress ?? []) put(path, content);
  for (const [path, content] of options.extraFiles ?? []) put(path, content);
  for (const report of REPORTS) put(`${CHECKLISTS}/${report}`, "# 验收报告\n");
  for (const extra of options.extraReports ?? []) put(`${CHECKLISTS}/${extra}`, "# 验收报告\n");
  if (options.removeAdr) rmSync(join(root, ADR));
  return root;
}

const has = (violations, text) =>
  assert.ok(violations.some((line) => line.includes(text)), `期望违规包含「${text}」，实际：${violations.join(" | ")}`);

test("基线：两条义务均登记、证据齐备、触发未过期 → 无违规", (t) => {
  const result = evaluate(fixture(t));
  assert.deepEqual(result.violations, []);
  assert.equal(result.obligations.length, 2);
  assert.ok(result.obligations.every((item) => item.state === "DEFERRED"));
  assert.equal(result.stageStates.M3, "passed");
  assert.equal(result.stageStates.M7, "pending");
  assert.equal(result.hardClauses.length, 3);
  assert.equal(result.latestProgress, "2026/2026-11-09.md");
});

test("漏登一条并行义务 → FAIL（核心防线）", (t) => {
  const ledger = baseLedger();
  ledger.obligations = ledger.obligations.filter((item) => item.stage !== "M7");
  const result = evaluate(fixture(t, { ledger }));
  has(result.violations, "并行义务 M7 未在台账登记");
  assert.ok(result.obligations.some((item) => item.stage === "M7" && item.state === "MISSING"));
});

test("证据文件不存在 → FAIL", (t) => {
  const result = evaluate(fixture(t, { removeAdr: true }));
  has(result.violations, "证据文件不存在");
});

test("缺少点名该义务的 Markdown 登记证据 → FAIL", (t) => {
  const ledger = baseLedger();
  for (const item of ledger.obligations) item.evidence = [ADR];
  const result = evaluate(fixture(t, {
    ledger,
    adr: "# ADR-0014\n只讲别的事情，没有阶段号。\n",
  }));
  has(result.violations, "缺少「点名该并行义务 M4 的 Markdown 登记证据」");
});

test("触发阶段已通过而义务仍未闭环 → FAIL", (t) => {
  const ledger = baseLedger();
  ledger.obligations[1].triggerStage = "M3";
  const result = evaluate(fixture(t, { ledger }));
  has(result.violations, "触发阶段 M3 已通过验收");
  assert.ok(result.obligations.some((item) => item.stage === "M7" && item.state === "OVERDUE"));
});

test("最近进度快照未点名并行义务 → FAIL", (t) => {
  const result = evaluate(fixture(t, { progress: "# 进度快照\n只写了主线任务。\n" }));
  has(result.violations, "未点名「并行义务」");
});

test("最近进度快照漏点名某条未闭环义务 → FAIL", (t) => {
  const result = evaluate(fixture(t, { progress: "## 并行义务\n- OBL-M4 deferred\n" }));
  has(result.violations, "未点名未闭环义务 OBL-M7");
});

test("roadmap 硬约束文本被删改 → FAIL", (t) => {
  const roadmap = ROADMAP_TEXT.replace("（不允许最后补）", "");
  const result = evaluate(fixture(t, { roadmap }));
  has(result.violations, "硬约束文本缺失或被改写：不允许最后补");
});

test("台账私自新增非 roadmap 义务 → FAIL", (t) => {
  const ledger = baseLedger();
  ledger.obligations.push({
    id: "OBL-M9", stage: "M9", status: "deferred", triggerStage: "M8",
    owner: "x", reason: "x", evidence: [ADR],
  });
  const result = evaluate(fixture(t, { ledger }));
  has(result.violations, "不是 roadmap 定义的并行义务");
});

test("台账使用 skipped 状态 → FAIL", (t) => {
  const ledger = baseLedger();
  ledger.obligations[0].status = "skipped";
  const result = evaluate(fixture(t, { ledger }));
  has(result.violations, "状态非法：skipped");
});

test("待启动阶段已有验收报告但 roadmap 未同步 → FAIL", (t) => {
  const result = evaluate(fixture(t, { extraReports: ["M5-验收报告-v1.md"] }));
  has(result.violations, "已有验收报告但 roadmap 状态仍为 pending");
});

test("已通过阶段缺验收报告 → FAIL", (t) => {
  const roadmap = ROADMAP_TEXT.replace("| **M2** | 编译 | x | M1 | **已通过** |", "| **M9** | 编译 | x | M1 | **已通过** |");
  const result = evaluate(fixture(t, { roadmap }));
  has(result.violations, "M9 标记「已通过」但缺少");
});

const RUN_ARTIFACT = "docs/06-verification/evaluation/零号冒烟-run-latest.json";

test("运行产物（非 md）可作为证据，只校验存在 → 不违规", (t) => {
  const ledger = baseLedger();
  for (const item of ledger.obligations) item.evidence.push(RUN_ARTIFACT);
  const result = evaluate(fixture(t, {
    ledger,
    extraFiles: [[RUN_ARTIFACT, '{"total":20,"passed":20}\n']],
  }));
  assert.deepEqual(result.violations, []);
});

test("证据使用绝对路径或路径穿越 → FAIL", (t) => {
  const ledger = baseLedger();
  ledger.obligations[0].evidence.push("../outside.md");
  ledger.obligations[1].evidence.push("/etc/passwd");
  const result = evaluate(fixture(t, { ledger }));
  has(result.violations, "证据路径必须是仓库内相对路径");
});

test("证据全是运行产物、无点名义务的文档登记 → FAIL", (t) => {
  const ledger = baseLedger();
  for (const item of ledger.obligations) item.evidence = [RUN_ARTIFACT];
  const result = evaluate(fixture(t, {
    ledger,
    extraFiles: [[RUN_ARTIFACT, '{"total":20}\n']],
  }));
  has(result.violations, "缺少「点名该并行义务");
});

test("触发阶段不在 roadmap 阶段总览 → FAIL（防止写错阶段导致静默失效）", (t) => {
  const ledger = baseLedger();
  ledger.obligations[1].triggerStage = "M99";
  const result = evaluate(fixture(t, { ledger }));
  has(result.violations, "触发阶段 M99 不在 roadmap 阶段总览中");
});

test("done 义务不因触发阶段已通过被误判过期", (t) => {
  const ledger = baseLedger();
  ledger.obligations[1].status = "done";
  ledger.obligations[1].triggerStage = "M3";
  const result = evaluate(fixture(t, { ledger }));
  assert.deepEqual(result.violations, []);
  assert.ok(result.obligations.some((item) => item.stage === "M7" && item.state === "DONE"));
});

test("跨年后取全局最新进度快照，不被旧年份目录遮住", (t) => {
  const result = evaluate(fixture(t, {
    extraProgress: [["docs/05-progress/2027/2027-01-02.md", "# 2027 快照\n只写了主线任务。\n"]],
  }));
  assert.equal(result.latestProgress, "2027/2027-01-02.md");
  has(result.violations, "未点名「并行义务」");
});

test("evaluate 不因被测目录缺失文件而抛异常", (t) => {
  const root = mkdtempSync(join(tmpdir(), "ontos-obligations-empty-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const result = evaluate(root);
  assert.ok(result.violations.length > 0);
  assert.ok(existsSync(root) === false || result.violations.some((line) => line.includes("roadmap")));
});
