#!/usr/bin/env node
/**
 * AgentScope 准入核验（M0 / W3 / T3.1，#3 判据的可执行部分）。
 *
 * 只做「不依赖模型凭据」的三件事，产出 #3 准入证据：
 *   1. 四个 artifact（core / harness / spring-boot-starter / agui）在目标版本上的坐标存在性；
 *   2. 与 Spring Boot 4.0.x + JDK 21 的兼容性（class 文件 major version、pom 里的 Boot 依赖）；
 *   3. 相对稳定版 2.0.4 的破坏性 API 差异（移除的 public 类 / public 方法）。
 *
 * 五类结构化终态的端到端重建需要真实模型凭据，由 scripts/poc/run-poc03.mjs 负责，
 * 不在本脚本范围内——本脚本通过不代表 #3 整体通过。
 *
 * 用法：
 *   node scripts/poc/verify-agentscope-admission.mjs [--output <json>]
 */
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const REPO = process.env.AGENTSCOPE_MAVEN_REPO ?? "D:/tianxy/soft/apache-maven-3.9.16/repository";
const TARGET = "2.1.0-BETA1";
const BASELINE = "2.0.4";
const GROUP_PATH = "io/agentscope";

// 判据 §7 #3 点名的四个 artifact；agui 侧实际落在两个坐标上，都核。
const ARTIFACTS = [
  { key: "core", artifact: "agentscope-core", required: true },
  { key: "harness", artifact: "agentscope-harness", required: true },
  { key: "spring-boot-starter", artifact: "agentscope-spring-boot-starter", required: true },
  { key: "agui-starter", artifact: "agentscope-agui-spring-boot-starter", required: true },
  { key: "agui-ext", artifact: "agentscope-extensions-agui", required: false },
];

const argOf = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};

const outputPath = resolve(
  argOf("--output", "docs/06-verification/poc/AgentScope-准入证据-raw.json"),
);

const fail = (message) => {
  console.error(`核验失败：${message}`);
  process.exit(1);
};

const run = (file, args) =>
  execFileSync(file, args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024, stdio: ["ignore", "pipe", "pipe"] });

const jarPath = (artifact, version) =>
  `${REPO}/${GROUP_PATH}/${artifact}/${version}/${artifact}-${version}.jar`;

const pomPath = (artifact, version) =>
  `${REPO}/${GROUP_PATH}/${artifact}/${version}/${artifact}-${version}.pom`;

/** 列出 jar 内全部 class 的 FQN。 */
const listClasses = (jar) => {
  if (!existsSync(jar)) return null;
  return run("unzip", ["-Z1", jar])
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.endsWith(".class"))
    .map((line) => line.slice(0, -".class".length).replaceAll("/", "."));
};

/** 用 javap 批量取 public 签名；返回 Map<类名, 方法签名行数组>。 */
const publicSignatures = (jar, classNames) => {
  const result = new Map();
  const BATCH = 50;
  for (let i = 0; i < classNames.length; i += BATCH) {
    const batch = classNames.slice(i, i + BATCH);
    let out;
    try {
      out = run("javap", ["-public", "-classpath", jar, ...batch]);
    } catch {
      continue; // 单个类无法解析时不影响整体
    }
    let current = null;
    for (const raw of out.split(/\r?\n/)) {
      const line = raw.trim();
      if (line.startsWith('Compiled from "')) continue;
      if (line === "" || line === "}") {
        current = null;
        continue;
      }
      // 类声明行：以 public/protected/private 开头且不含 "("，或 interface/enum/record 声明
      if (current === null) {
        const match = line.match(/^(?:public |protected |private )?(?:final |abstract |static )*(?:class|interface|enum|record|@interface) ([\w.$]+)/);
        if (match) {
          current = match[1];
          if (!result.has(current)) result.set(current, []);
        }
        continue;
      }
      if (line.startsWith("public ") || line.startsWith("protected ")) {
        result.get(current).push(line.replace(/;\s*$/, ""));
      }
    }
  }
  return result;
};

const isPublicTopLevel = (jar, className) => {
  if (!className.includes(".") || className.includes("$")) return false;
  try {
    const out = run("javap", ["-public", "-classpath", jar, className]);
    return /^\s*public\s/m.test(out);
  } catch {
    return false;
  }
};

/** 读取 jar 内任一 class 的 major version（65 = Java 21）。 */
const classFileMajor = (jar) => {
  const entries = run("unzip", ["-Z1", jar]).split("\n").filter((l) => l.endsWith(".class"));
  if (entries.length === 0) return null;
  const buf = execFileSync("unzip", ["-p", jar, entries[0]], { maxBuffer: 1024 * 1024 });
  return buf.readUInt16BE(6);
};

const pomText = (artifact, version) => {
  const p = pomPath(artifact, version);
  return existsSync(p) ? run("cat", [p]) : null;
};

const extractBootDeps = (pom) => {
  if (!pom) return [];
  const deps = [];
  const re = /<dependency>([\s\S]*?)<\/dependency>/g;
  let m;
  while ((m = re.exec(pom)) !== null) {
    const block = m[1];
    const group = block.match(/<groupId>([^<]+)<\/groupId>/)?.[1] ?? "";
    const artifact = block.match(/<artifactId>([^<]+)<\/artifactId>/)?.[1] ?? "";
    const version = block.match(/<version>([^<]+)<\/version>/)?.[1] ?? "";
    if (group.includes("springframework.boot") || group.includes("springframework")) {
      deps.push({ group, artifact, version });
    }
  }
  return deps;
};

const extractParent = (pom) => {
  if (!pom) return null;
  const block = pom.match(/<parent>([\s\S]*?)<\/parent>/)?.[1];
  if (!block) return null;
  return {
    groupId: block.match(/<groupId>([^<]+)<\/groupId>/)?.[1] ?? "",
    artifactId: block.match(/<artifactId>([^<]+)<\/artifactId>/)?.[1] ?? "",
    version: block.match(/<version>([^<]+)<\/version>/)?.[1] ?? "",
  };
};

// ---------- 1. 坐标存在性 + class 规模 ----------
const artifacts = [];
let missingRequired = [];

for (const spec of ARTIFACTS) {
  const targetJar = jarPath(spec.artifact, TARGET);
  const baselineJar = jarPath(spec.artifact, BASELINE);
  const targetClasses = listClasses(targetJar);
  const baselineClasses = listClasses(baselineJar);
  const entry = {
    key: spec.key,
    artifact: spec.artifact,
    required: spec.required,
    target: {
      version: TARGET,
      jar: existsSync(targetJar),
      pom: existsSync(pomPath(spec.artifact, TARGET)),
      classes: targetClasses?.length ?? 0,
      classFileMajor: targetClasses ? classFileMajor(targetJar) : null,
    },
    baseline: {
      version: BASELINE,
      jar: existsSync(baselineJar),
      classes: baselineClasses?.length ?? 0,
    },
  };
  if (spec.required && (!entry.target.jar || !entry.target.pom)) {
    missingRequired.push(spec.artifact);
  }
  artifacts.push(entry);
}

if (missingRequired.length > 0) {
  fail(`必需坐标缺失：${missingRequired.join(", ")}（版本 ${TARGET}）`);
}

// ---------- 2. JDK / Boot 兼容 ----------
const majors = artifacts.map((a) => a.target.classFileMajor).filter((v) => v != null);
const maxMajor = majors.length ? Math.max(...majors) : null;
const JDK_21_MAJOR = 65;
const jdk = {
  maxClassFileMajor: maxMajor,
  javaVersion: maxMajor == null ? null : maxMajor - 44,
  runsOnJdk21: maxMajor != null && maxMajor <= JDK_21_MAJOR,
};

const bootDeps = extractBootDeps(pomText("agentscope-spring-boot-starter", TARGET));
const parent = extractParent(pomText("agentscope-spring-boot-starter", TARGET));
const bootAlignment = {
  parent,
  springDependencies: bootDeps,
  // Boot 4 的坐标前缀是 org.springframework.boot；出现 3.x 版本即需人工确认
  declaresSpringBoot4: bootDeps.some(
    (d) => d.group.includes("springframework.boot") && /^4\./.test(d.version),
  ),
};

// ---------- 3. 相对 2.0.4 的破坏性 API 差异 ----------
const apiDiff = [];
for (const entry of artifacts) {
  const spec = ARTIFACTS.find((s) => s.key === entry.key);
  const targetJar = jarPath(spec.artifact, TARGET);
  const baselineJar = jarPath(spec.artifact, BASELINE);
  if (!existsSync(targetJar) || !existsSync(baselineJar)) {
    apiDiff.push({ artifact: spec.artifact, skipped: "缺少任一版本 jar" });
    continue;
  }
  const targetSet = new Set(listClasses(targetJar));
  const baselineSet = new Set(listClasses(baselineJar));

  const removed = [...baselineSet].filter((c) => !targetSet.has(c));
  const added = [...targetSet].filter((c) => !baselineSet.has(c));

  // 只对顶层类做 public 判定，内部类（$）在类级清单里已可见
  const removedPublicTopLevel = removed
    .filter((c) => !c.includes("$"))
    .filter((c) => isPublicTopLevel(baselineJar, c));

  // 交集顶层类做方法级 diff
  const commonTopLevel = [...targetSet].filter((c) => baselineSet.has(c) && !c.includes("$"));
  const targetSigs = publicSignatures(targetJar, commonTopLevel);
  const baselineSigs = publicSignatures(baselineJar, commonTopLevel);

  const removedMethods = [];
  for (const [className, sigs] of baselineSigs) {
    const after = new Set(targetSigs.get(className) ?? []);
    const gone = sigs.filter((s) => !after.has(s));
    if (gone.length > 0) removedMethods.push({ class: className, methods: gone });
  }

  apiDiff.push({
    artifact: spec.artifact,
    removedClassCount: removed.length,
    removedClasses: removed,
    removedPublicTopLevelClasses: removedPublicTopLevel,
    addedClassCount: added.length,
    addedTopLevelClasses: added.filter((c) => !c.includes("$")),
    classesWithRemovedPublicMethods: removedMethods.length,
    removedPublicMethods: removedMethods,
  });
}

// ---------- 3b. 判据点名的 skill-activation / toolkit-binding API 交叉核对 ----------
// 判据原文假定这两个 API 在 2.0.4 → 2.1.0-BETA1 之间被移除。实测需要独立核，
// 不能把判据表述当结论抄进报告。
const skillToolkitClasses = (version, artifact) => {
  const jar = jarPath(artifact, version);
  if (!existsSync(jar)) return null;
  return listClasses(jar)
    .filter((c) => !c.includes("$"))
    .filter((c) => /\.(skill|tool)\.[A-Za-z]*Skill|\.tool\.Toolkit|\.tool\.SkillToolGroup/i.test(c))
    .sort();
};

const skillBaseline = [
  ...(skillToolkitClasses(BASELINE, "agentscope-core") ?? []),
  ...(skillToolkitClasses(BASELINE, "agentscope-harness") ?? []),
];
const skillTarget = [
  ...(skillToolkitClasses(TARGET, "agentscope-core") ?? []),
  ...(skillToolkitClasses(TARGET, "agentscope-harness") ?? []),
];
const skillToolkitCheck = {
  baselineCount: skillBaseline.length,
  targetCount: skillTarget.length,
  removed: skillBaseline.filter((c) => !skillTarget.includes(c)),
  added: skillTarget.filter((c) => !skillBaseline.includes(c)),
};
skillToolkitCheck.identical = skillToolkitCheck.removed.length === 0 && skillToolkitCheck.added.length === 0;

// ---------- 报告 ----------
const breaking = apiDiff.reduce(
  (sum, a) => sum + (a.removedPublicTopLevelClasses?.length ?? 0) + (a.classesWithRemovedPublicMethods ?? 0),
  0,
);

const report = {
  generatedAt: new Date().toISOString(),
  scope: "T3.1 准入的可执行部分（坐标 / JDK / Boot / 破坏性 API 差异）；不含五类终态重建",
  targetVersion: TARGET,
  baselineVersion: BASELINE,
  java: process.env.JAVA_HOME ?? "runtime",
  artifacts,
  jdk,
  bootAlignment,
  apiDiff,
  skillToolkitCheck,
  summary: {
    artifactsAllPresent: artifacts.filter((a) => a.required).every((a) => a.target.jar && a.target.pom),
    runsOnJdk21: jdk.runsOnJdk21,
    breakingChangeSignals: breaking,
    skillToolkitApiUnchanged: skillToolkitCheck.identical,
  },
};

mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");

console.log("AgentScope 准入核验（T3.1 / #3 可执行部分）");
console.log(`  目标版本 ${TARGET} vs 稳定版 ${BASELINE}`);
console.log("\n  坐标与规模");
for (const a of artifacts) {
  const flag = a.target.jar ? "OK" : "MISSING";
  console.log(
    `    [${flag}] ${a.artifact.padEnd(38)} classes ${String(a.target.classes).padStart(4)}  base ${String(a.baseline.classes).padStart(4)}  major ${a.target.classFileMajor ?? "-"}`,
  );
}
console.log("\n  JDK 兼容");
console.log(`    class 文件最大 major = ${jdk.maxClassFileMajor}（Java ${jdk.javaVersion}），可在 JDK 21 运行：${jdk.runsOnJdk21 ? "是" : "否"}`);
console.log("\n  Spring Boot 依赖（starter POM）");
for (const d of bootAlignment.springDependencies) {
  console.log(`    ${d.group}:${d.artifact}:${d.version}`);
}
if (bootAlignment.springDependencies.length === 0) console.log("    未在 POM 中直接声明 Spring 依赖（由 BOM 或传递依赖决定）");

console.log("\n  破坏性 API 差异（相对 2.0.4）");
for (const d of apiDiff) {
  if (d.skipped) {
    console.log(`    ${d.artifact}: 跳过（${d.skipped}）`);
    continue;
  }
  console.log(
    `    ${d.artifact}: 移除类 ${d.removedClassCount}（public 顶层 ${d.removedPublicTopLevelClasses.length}）、新增类 ${d.addedClassCount}、public 方法减少的类 ${d.classesWithRemovedPublicMethods}`,
  );
  for (const c of d.removedPublicTopLevelClasses.slice(0, 10)) console.log(`      - 移除 ${c}`);
  for (const m of d.removedPublicMethods.slice(0, 5)) {
    console.log(`      - ${m.class}: ${m.methods.length} 个 public 方法签名变化`);
  }
}

console.log(`\n  原始输出：${outputPath}`);
console.log("\n  判据交叉核对（skill-activation / toolkit-binding）");
console.log(
  `    2.0.4 ${skillToolkitCheck.baselineCount} 个 / ${TARGET} ${skillToolkitCheck.targetCount} 个；移除 ${skillToolkitCheck.removed.length}、新增 ${skillToolkitCheck.added.length}`,
);
console.log(
  `    结论：${skillToolkitCheck.identical ? "两版该类 API 一致，判据中「已移除」的表述与实测不符" : "存在差异，需逐项确认"}`,
);
if (skillToolkitCheck.removed.length) {
  for (const c of skillToolkitCheck.removed.slice(0, 20)) console.log(`      - 移除 ${c}`);
}

console.log(
  `\n  小结：坐标 ${report.summary.artifactsAllPresent ? "齐全" : "缺失"}；JDK21 ${report.summary.runsOnJdk21 ? "兼容" : "不兼容"}；破坏性信号 ${breaking} 处`,
);
console.log("  注意：本脚本不覆盖五类结构化终态重建，#3 整体判据仍未关闭。");
