/**
 * 并行义务与阶段状态检查（治理强制项）。
 * 运行：node docs/00-governance/check-parallel-obligations.mjs [--json]
 * 规则来源：AGENTS.md §6.4「并行义务纪律」、ADR-0014、docs/README.md §7.8。
 *
 * 目的：roadmap 里「并行 / 同步建设 / 不许后置」类约束必须被逐阶段跟踪，
 * 不能因为状态栏写「待启动」就静默漏做。任何未登记、证据失效、触发过期、
 * 进度未点名或阶段状态不同步，一律 FAIL；禁止为跑通而放宽本脚本。
 */
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const DEFAULT_ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const ROADMAP = "docs/03-plans/roadmap/Ontos开发计划-阶段性功能拆解-v1.md";
const LEDGER = "docs/00-governance/parallel-obligations.json";
const CHECKLISTS = "docs/06-verification/checklists";
const PROGRESS = "docs/05-progress/2026";
const REQUIRED_TEXTS = ["并行支线", "不允许最后补", "安全与评测不许后置"];

function mentions(text, stage) {
  return new RegExp(`\\b${stage}\\b`).test(text || "");
}

export function evaluate(root = DEFAULT_ROOT) {
  const violations = [];
  const obligations = [];
  const stageStates = {};
  const hardClauses = [];
  let latestProgress = null;

  const fail = (message) => violations.push(message);
  const abs = (p) => join(root, p);
  const read = (p) => (existsSync(abs(p)) ? readFileSync(abs(p), "utf8") : null);

  const roadmap = read(ROADMAP);
  if (roadmap === null) {
    fail(`缺少 roadmap：${ROADMAP}`);
    return { violations, obligations, stageStates, hardClauses, latestProgress };
  }

  // 1. 硬约束文本不得被删改（防止通过改文档绕过纪律）
  for (const token of REQUIRED_TEXTS) {
    if (!roadmap.includes(token)) fail(`roadmap 硬约束文本缺失或被改写：${token}`);
    else hardClauses.push(token);
  }

  // 2. 从阶段总览的依赖 / 目标列抽取并行义务，并读取阶段状态
  //    只看 cells[1..3]，避免把状态列里的「M4 可并行」之类描述误判成义务主体。
  const lines = roadmap.split(/\r?\n/);
  const start = lines.findIndex((line) => /^##\s*1\.\s*阶段总览/.test(line));
  if (start < 0) fail("roadmap 缺少「## 1. 阶段总览」，无法确定并行义务与阶段状态");
  let end = -1;
  if (start >= 0) end = lines.findIndex((line, i) => i > start && /^---\s*$/.test(line));
  const table = start >= 0
    ? lines.slice(start + 1, end < 0 ? lines.length : end).filter((line) => /^\s*\|/.test(line))
    : [];

  const roadmapObligations = new Map();
  for (const line of table) {
    const cells = line.split("|").slice(1, -1).map((cell) => cell.trim());
    if (cells.length < 5) continue;
    const stage = (cells[0].match(/\*\*(M\d+)\*\*/) || [])[1];
    if (!stage) continue;
    const statusCell = cells[4] || "";
    stageStates[stage] = /已通过/.test(statusCell)
      ? "passed"
      : /进行中/.test(statusCell) ? "active" : "pending";
    const scope = cells.slice(1, 4).join(" ");
    if (/并行|同步建设|不许后置/.test(scope)) roadmapObligations.set(stage, scope);
  }
  if (roadmapObligations.size === 0) {
    fail("未能从 roadmap 阶段总览抽取到任何并行义务，检查脚本已失效");
  }

  // 3. 台账结构与义务集合必须与 roadmap 完全一致
  const ledgerRaw = read(LEDGER);
  let ledger = null;
  if (ledgerRaw === null) {
    fail(`缺少并行义务台账：${LEDGER}`);
  } else {
    try {
      ledger = JSON.parse(ledgerRaw);
    } catch (error) {
      fail(`并行义务台账不是合法 JSON：${error.message}`);
    }
  }
  if (ledger && ledger.schemaVersion !== 1) {
    fail(`台账 schemaVersion 不支持：${ledger.schemaVersion}`);
  }
  if (ledger && !Array.isArray(ledger.obligations)) fail("台账缺少 obligations 数组");
  const items = ledger && Array.isArray(ledger.obligations) ? ledger.obligations : [];

  const byStage = new Map();
  for (const item of items) {
    if (!item || !item.id || !item.stage) {
      fail("台账存在缺少 id / stage 的义务条目");
      continue;
    }
    if (byStage.has(item.stage)) fail(`台账中阶段 ${item.stage} 重复登记`);
    byStage.set(item.stage, item);
  }
  for (const stage of roadmapObligations.keys()) {
    if (!byStage.has(stage)) {
      fail(`并行义务 ${stage} 未在台账登记（漏做防线）：${roadmapObligations.get(stage)}`);
    }
  }
  for (const stage of byStage.keys()) {
    if (!roadmapObligations.has(stage)) {
      fail(`台账中的 ${stage} 不是 roadmap 定义的并行义务（禁止私自增删）`);
    }
  }

  // 4. 逐条校验状态、证据与触发阶段
  const deferred = [];
  for (const [stage, item] of byStage) {
    let state = "OK";
    if (!["done", "deferred"].includes(item.status)) {
      fail(`${item.id} 状态非法：${item.status}（只允许 done / deferred）`);
      state = "INVALID";
    }
    if (item.status === "deferred") deferred.push(item);
    if (!item.reason || !item.triggerStage || !item.owner) {
      fail(`${item.id} 缺少 reason / triggerStage / owner`);
      state = "INVALID";
    }
    const evidences = Array.isArray(item.evidence) ? item.evidence : [];
    if (evidences.length === 0) {
      fail(`${item.id} 未提供任何证据文件`);
      state = "INVALID";
    }
    for (const path of evidences) {
      if (typeof path !== "string" || !path.startsWith("docs/")) {
        fail(`${item.id} 证据路径必须以 docs/ 开头：${path}`);
        state = "INVALID";
        continue;
      }
      const text = read(path);
      if (text === null) {
        fail(`${item.id} 证据文件不存在：${path}`);
        state = "INVALID";
        continue;
      }
      if (!mentions(text, stage)) {
        fail(`${item.id} 证据未点名该并行义务 ${stage}：${path}`);
        state = "INVALID";
      }
    }
    if (item.triggerStage && stageStates[item.triggerStage] === "passed") {
      fail(`${item.id} 触发阶段 ${item.triggerStage} 已通过验收，义务仍为 ${item.status}；必须闭环或新发 ADR 重定触发点`);
      state = "OVERDUE";
    }
    if (!roadmapObligations.has(stage)) state = "MISSING";
    else if (state === "OK") state = item.status === "done" ? "DONE" : "DEFERRED";
    obligations.push({
      id: item.id,
      stage,
      state,
      triggerStage: item.triggerStage ?? null,
      status: item.status,
      evidenceCount: evidences.length,
    });
  }
  for (const stage of roadmapObligations.keys()) {
    if (!byStage.has(stage)) {
      obligations.push({ id: null, stage, state: "MISSING", triggerStage: null, status: null, evidenceCount: 0 });
    }
  }

  // 5. 最近一次进度快照必须点名所有未闭环义务（阶段收口强制核对）
  const progressDir = abs(PROGRESS);
  const progressFiles = existsSync(progressDir)
    ? readdirSync(progressDir).filter((name) => /^\d{4}-\d{2}-\d{2}\.md$/.test(name)).sort()
    : [];
  if (progressFiles.length === 0) {
    fail(`缺少进度快照目录或文件：${PROGRESS}`);
  } else {
    latestProgress = progressFiles[progressFiles.length - 1];
    const text = read(`${PROGRESS}/${latestProgress}`);
    if (!/并行义务/.test(text || "")) {
      fail(`最近一次进度快照 ${latestProgress} 未点名「并行义务」（阶段收口必须逐项核对）`);
    }
    for (const item of deferred) {
      if (!mentions(text, item.stage)) {
        fail(`最近一次进度快照 ${latestProgress} 未点名未闭环义务 ${item.id}（${item.stage}）`);
      }
    }
  }

  // 6. 阶段状态与验收报告一致：已通过必须有报告，未通过不得已有报告
  const checklistDir = abs(CHECKLISTS);
  const reports = existsSync(checklistDir) ? readdirSync(checklistDir) : [];
  for (const [stage, status] of Object.entries(stageStates)) {
    const hasReport = reports.some((name) => new RegExp(`^${stage}-验收报告-v\\d+\\.md$`).test(name));
    if (status === "passed" && !hasReport) {
      fail(`${stage} 标记「已通过」但缺少 M<n>-验收报告-vN.md`);
    }
    if (status !== "passed" && hasReport) {
      fail(`${stage} 已有验收报告但 roadmap 状态仍为 ${status}，状态未同步`);
    }
  }

  return { violations, obligations, stageStates, hardClauses, latestProgress };
}

const invoked = (process.argv[1] || "").replace(/\\/g, "/");
const isMain = invoked.endsWith("/check-parallel-obligations.mjs");
if (isMain) {
  const result = evaluate();
  if (process.argv.includes("--json")) {
    console.log(JSON.stringify(result, null, 2));
  } else {
    console.log("并行义务与阶段状态检查（治理强制项）");
    for (const item of result.obligations) {
      const trigger = item.triggerStage ? `触发 ${item.triggerStage}` : "无触发阶段";
      console.log(`  [${item.state}] ${item.id ?? "(未登记)"} ${item.stage} — ${trigger}，证据 ${item.evidenceCount} 份`);
    }
    const passed = Object.entries(result.stageStates)
      .filter(([, status]) => status === "passed")
      .map(([stage]) => stage);
    const total = Object.keys(result.stageStates).length;
    console.log(`  阶段状态：${passed.join(" / ") || "无"} 已通过，其余 ${total - passed.length} 个待启动 / 进行中`);
    console.log(`  roadmap 硬约束文本：${result.hardClauses.length} / ${REQUIRED_TEXTS.length} 存在`);
    console.log(`  最近进度快照：${result.latestProgress ?? "无"}`);
    if (result.violations.length > 0) {
      console.error(`结果：FAIL（${result.violations.length} 项违规）`);
      for (const violation of result.violations) console.error(`- ${violation}`);
    } else {
      const left = result.obligations.filter((item) => item.state === "DEFERRED").length;
      console.log(`结果：PASS（0 违规；deferred ${left} 项，须按触发阶段闭环）`);
    }
  }
  process.exit(result.violations.length > 0 ? 1 : 0);
}
