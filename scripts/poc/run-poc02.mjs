/**
 * PoC #2「发布快照可复现」系统级探针（M0 / W2 / T2.2）。
 *
 * 判据（技术选型 v1.1 §7 #2）：同一 DSL + 同一 manifest_id/content_hash，重复 100 次且跨 2 次
 * 应用重启，规范化 SQL 与参数逐字一致率 100%。
 *
 * 本探针不做单元级模拟：直接启动真实 jar，走 HTTP `/api/v1/query/compile`，
 * 每次重启都是一个全新的 JVM 进程 + 全新 Spring 上下文 + 从 System DB 重新加载的发布清单。
 *
 * 运行：node scripts/poc/run-poc02.mjs [--no-build]
 * 产物：docs/06-verification/poc/PoC-02-raw.json
 */
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(fileURLToPath(new URL("..", import.meta.url))); // scripts/
const repo = resolve(root, "..");
const jar = resolve(repo, "ontos/ontos-server/target/ontos-server-0.1.0-SNAPSHOT.jar");
const evidenceDir = resolve(repo, "docs/06-verification/poc");
const PORT = 18080;
const BASE = `http://127.0.0.1:${PORT}`;
const REPEAT_PER_RUN = 100;
const RESTARTS = 2; // 首次启动 + 2 次重启 = 3 个独立进程
const MANIFEST_ID = "2026.11.06-demo";

/** 固定 DSL：含两个维度、IN 过滤、时间窗与 limit，覆盖 SQL 与全部绑定值路径。 */
const DSL = {
  manifestId: MANIFEST_ID,
  mode: "QUERY",
  metricId: "metric.gmv",
  dimensions: ["dim.region", "dim.channel"],
  filters: [{ fieldId: "dim.channel", operator: "IN", values: ["线上", "线下"] }],
  timeRange: {
    field: "dim.order_date",
    from: "2026-01-01T00:00:00Z",
    to: "2026-12-31T23:59:59Z",
  },
  limit: 50,
};

const HEADERS = {
  "content-type": "application/json",
  // 显式身份头：即使 local profile 打开了 dev 头，也让每次运行的请求环境完全一致
  "X-Ontos-Tenant": "demo",
  "X-Ontos-User": "demo",
  "X-Ontos-Session": "poc-02",
  "X-Ontos-Turn": "0",
};

const args = new Set(process.argv.slice(2));
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

const sha256 = (text) => createHash("sha256").update(text).digest("hex");

function ensureJar() {
  if (existsSync(jar) && args.has("--no-build")) return;
  console.log("构建 jar（mvn -B -q -DskipTests package）...");
  const build = spawnSync("mvn -B -q -DskipTests package", {
    cwd: resolve(repo, "ontos"),
    shell: true,
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
  });
  if (build.status !== 0 || !existsSync(jar)) {
    console.error((build.stdout || "") + (build.stderr || ""));
    throw new Error("jar 构建失败，无法继续");
  }
}

async function waitHealthy(child, logLines) {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    await sleep(1000);
    if (child.exitCode !== null) {
      throw new Error(`服务进程提前退出（code=${child.exitCode}）：\n${logLines.slice(-15).join("\n")}`);
    }
    try {
      const response = await fetch(`${BASE}/actuator/health`, { signal: AbortSignal.timeout(1500) });
      const body = await response.json();
      if (body.status === "UP") return body;
    } catch {
      // 端口尚未监听
    }
  }
  throw new Error(`服务启动超时：\n${logLines.slice(-15).join("\n")}`);
}

async function waitPortClosed() {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    try {
      await fetch(`${BASE}/actuator/health`, { signal: AbortSignal.timeout(500) });
    } catch {
      return;
    }
    await sleep(500);
  }
  throw new Error("服务停止后端口仍可访问");
}

async function startServer(runIndex) {
  const logLines = [];
  const child = spawn(
    "java",
    [
      "-jar",
      jar,
      "--spring.profiles.active=local",
      "--ontos.agent.enabled=false", // 关掉模型依赖，PoC 只验编译确定性
      `--server.port=${PORT}`,
    ],
    { env: { ...process.env, SERVER__PORT: String(PORT) }, stdio: ["ignore", "pipe", "pipe"] },
  );
  const collect = (chunk) => {
    for (const line of chunk.toString().split(/\r?\n/)) {
      if (line.trim()) logLines.push(line.trim());
    }
  };
  child.stdout.on("data", collect);
  child.stderr.on("data", collect);
  const startedAt = new Date().toISOString();
  const health = await waitHealthy(child, logLines);
  return { child, logLines, pid: child.pid, startedAt, health, runIndex };
}

async function stopServer(server) {
  server.child.kill();
  await waitPortClosed();
}

async function compileOnce() {
  const response = await fetch(`${BASE}/api/v1/query/compile`, {
    method: "POST",
    headers: HEADERS,
    body: JSON.stringify(DSL),
    signal: AbortSignal.timeout(10000),
  });
  const body = await response.json();
  if (!response.ok || body.code !== 0) {
    throw new Error(`编译失败 HTTP ${response.status}：${JSON.stringify(body).slice(0, 400)}`);
  }
  return body.data;
}

/** 判据只比对「编译产物」：SQL、绑定值、版本锚点与生效上限。 */
function comparable(data) {
  return {
    manifestId: data.manifestId,
    contentHash: data.contentHash,
    metricId: data.metricId,
    dimensions: data.dimensions,
    dialect: data.dialect,
    sql: data.sql,
    bindValues: data.bindValues,
    appliedLimit: data.appliedLimit,
  };
}

async function runOnce(runIndex) {
  const server = await startServer(runIndex);
  try {
    const digests = new Set();
    let sample = null;
    for (let i = 0; i < REPEAT_PER_RUN; i += 1) {
      const data = comparable(await compileOnce());
      if (!sample) sample = data;
      digests.add(sha256(stableStringify(data)));
    }
    return {
      run: runIndex,
      pid: server.pid,
      startedAt: server.startedAt,
      health: server.health.status,
      compiles: REPEAT_PER_RUN,
      distinctDigests: digests.size,
      digest: [...digests][0],
      inRunIdentical: digests.size === 1,
      payload: sample,
    };
  } finally {
    await stopServer(server);
  }
}

function javaVersion() {
  const result = spawnSync("java -version", { shell: true, encoding: "utf8" });
  return ((result.stderr || "") + (result.stdout || "")).split("\n")[0].trim();
}

async function main() {
  ensureJar();
  mkdirSync(evidenceDir, { recursive: true });

  const runs = [];
  for (let index = 1; index <= RESTARTS + 1; index += 1) {
    console.log(`第 ${index} 个进程：启动 → 编译 ${REPEAT_PER_RUN} 次 → 停止`);
    runs.push(await runOnce(index));
  }

  const digests = new Set(runs.map((run) => run.digest));
  const inRunOk = runs.every((run) => run.inRunIdentical);
  const crossRestartIdentical = digests.size === 1;
  const totalCompiles = runs.reduce((sum, run) => sum + run.compiles, 0);
  const identicalCompiles = inRunOk && crossRestartIdentical
    ? totalCompiles
    : runs.reduce((sum, run) => sum + (run.inRunIdentical ? run.compiles : 0), 0);
  const agreementRate = `${((identicalCompiles / totalCompiles) * 100).toFixed(2)}%`;
  const pass = inRunOk && crossRestartIdentical;

  const raw = {
    poc: "#2 发布快照可复现",
    task: "M0/T2.2",
    level: "系统级（真实 jar + HTTP + System DB 清单加载）",
    executedAt: new Date().toISOString(),
    environment: {
      java: javaVersion(),
      os: `${process.platform} ${process.arch}`,
      database: "PostgreSQL 18.6-5 @ 127.0.0.1:5432",
      serverPort: PORT,
      profile: "local",
      manifestId: MANIFEST_ID,
    },
    dsl: DSL,
    restarts: RESTARTS,
    processes: runs.length,
    repeatPerProcess: REPEAT_PER_RUN,
    totalCompiles,
    runs,
    comparison: {
      inRunIdentical: inRunOk,
      crossRestartIdentical,
      distinctDigestsAcrossRuns: digests.size,
      agreementRate,
    },
    verdict: pass ? "PASS" : "FAIL",
  };

  const output = resolve(evidenceDir, "PoC-02-raw.json");
  writeFileSync(output, `${JSON.stringify(raw, null, 2)}\n`, "utf8");

  console.log("\nPoC #2 结果");
  for (const run of runs) {
    console.log(
      `  run${run.run} pid=${run.pid} 启动=${run.startedAt} 编译=${run.compiles} `
        + `进程内不同摘要=${run.distinctDigests} 摘要=${run.digest.slice(0, 16)}…`,
    );
  }
  console.log(`  跨 ${RESTARTS} 次重启摘要种类：${digests.size}（应为 1）`);
  console.log(`  逐字一致率：${agreementRate}（${identicalCompiles}/${totalCompiles}）`);
  console.log(`  结论：${raw.verdict}    证据：${output.replace(repo + "\\", "").replace(repo + "/", "")}`);
  process.exit(pass ? 0 : 1);
}

main().catch((error) => {
  console.error(`探针执行失败：${error.message}`);
  process.exit(2);
});
