/**
 * docs 治理检查：层级、命名、相对链接、目录说明。
 * 运行：node docs/00-governance/check-docs.mjs
 * 规则来源：docs/README.md（文档规范与总索引）。
 */
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { dirname, extname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const docsRoot = resolve(fileURLToPath(new URL("..", import.meta.url)));
const violations = [];
const fail = (message) => violations.push(message);

const LEVEL_ONE = [
  "00-governance",
  "01-decisions",
  "02-solutions",
  "03-plans",
  "04-specs",
  "05-progress",
  "06-verification",
  "07-references",
  "99-archive",
];

// 1. docs 根目录只允许 README.md
for (const entry of readdirSync(docsRoot, { withFileTypes: true })) {
  if (entry.isFile() && entry.name.endsWith(".md") && entry.name !== "README.md") {
    fail(`docs 根目录不允许平铺文档：${entry.name}（请归入 00-07 目录）`);
  }
}

// 2. 一级目录与各自的 README
for (const directory of LEVEL_ONE) {
  const path = join(docsRoot, directory);
  if (!existsSync(path)) {
    fail(`缺少一级目录：${directory}`);
    continue;
  }
  if (!existsSync(join(path, "README.md"))) {
    fail(`一级目录缺少 README.md：${directory}`);
  }
  for (const entry of readdirSync(path, { withFileTypes: true })) {
    if (!entry.isDirectory() || !/^\d{2}-/.test(entry.name)) continue;
    fail(`一级目录下不应再出现编号目录（层级上限 3 层）：${directory}/${entry.name}`);
  }
}

// 3. 收集全部 md
const files = [];
const walk = (directory) => {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      walk(path);
    } else if (entry.isFile() && extname(entry.name) === ".md") {
      files.push(path);
    }
  }
};
walk(docsRoot);

const rel = (path) => relative(docsRoot, path).split(sep).join("/");

// 4. 命名规则
const namingRules = [
  /^README\.md$/,
  /-模板\.md$/,
  /^ADR-\d{4}-.+\.md$/,
  /^PoC-\d{2}-.+\.md$/,
  /^\d{4}-\d{2}-\d{2}\.md$/,
  /^\d{4}-W\d{2}\.md$/,
  /-v\d+(\.\d+)?\.md$/,
];
for (const path of files) {
  const name = path.split(sep).pop();
  if (!namingRules.some((rule) => rule.test(name))) {
    fail(`文件名不符合命名规范（需 -vN / ADR-NNNN / PoC-NN / 日期）：${rel(path)}`);
  }
}

// 5. 相对链接可解析（跳过代码块、外部链接、锚点）
const linkPattern = /\[[^\]]*\]\(([^)]+)\)/g;
const legacyAllowedLinks = new Set([
  "docs/review-ontology-query-plan-v1.4.md",
  "docs/review-tech-selection-v1.0.md",
]);
let checkedLinks = 0;

for (const path of files) {
  const lines = readFileSync(path, "utf8").split("\n");
  let inCodeBlock = false;
  for (const line of lines) {
    if (/^\s*(?:>\s*)?```/.test(line)) {
      inCodeBlock = !inCodeBlock;
      continue;
    }
    if (inCodeBlock) continue;
    for (const match of line.matchAll(linkPattern)) {
      const target = match[1].trim();
      if (/^(https?:|mailto:|#)/.test(target)) continue;
      if (legacyAllowedLinks.has(target)) continue;
      const clean = target.split("#")[0];
      if (!clean) continue;
      checkedLinks += 1;
      const resolved = resolve(dirname(path), clean);
      if (!existsSync(resolved)) {
        fail(`失效引用：${rel(path)} → ${target}`);
      }
    }
  }
}

// 6. 总索引必须存在
if (!existsSync(join(docsRoot, "README.md"))) {
  fail("缺少总索引 docs/README.md");
}

if (violations.length > 0) {
  console.error(`docs 治理检查失败（${violations.length} 项）：`);
  for (const violation of violations) console.error(`- ${violation}`);
  process.exit(1);
}

console.log(
  `docs 治理检查通过：${files.length} 份文档，${checkedLinks} 条相对链接，${LEVEL_ONE.length} 个一级目录。`,
);
