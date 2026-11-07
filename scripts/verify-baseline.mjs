/**
 * M0 T1.2 基线自检：把四类检查串成一条命令，分别记录结果。
 * 运行：node scripts/verify-baseline.mjs
 *
 * 报告区分 PASS / FAIL / SKIP；SKIP 不算通过。
 * 可执行 jar 探活固定使用无数据库模式，不受 PG18 环境阻塞。
 */
import { spawn, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(fileURLToPath(new URL("..", import.meta.url)));
const results = [];

const tail = (text, lines = 25) =>
  (text || "").trim().split("\n").slice(-lines).join("\n");

function check(name, cwd, command) {
  const started = Date.now();
  const result = spawnSync(command, {
    cwd: resolve(root, cwd),
    shell: true,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
  const ok = result.status === 0;
  const seconds = ((Date.now() - started) / 1000).toFixed(1);
  results.push({ name, ok, detail: ok ? `${seconds}s` : "见下方输出" });
  if (!ok) {
    console.error(`\n--- ${name} 失败输出 ---`);
    console.error(tail((result.stdout || "") + (result.stderr || "")));
  }
  return ok;
}

async function bootProbe() {
  const name = "可执行 jar 启动探活";
  const jar = resolve(root, "ontos/ontos-server/target/ontos-server-0.1.0-SNAPSHOT.jar");

  if (!existsSync(jar)) {
    const build = spawnSync("mvn -B -q -DskipTests package", {
      cwd: resolve(root, "ontos"),
      shell: true,
      encoding: "utf8",
    });
    if (build.status !== 0 || !existsSync(jar)) {
      results.push({
        name,
        ok: false,
        skipped: true,
        detail: "jar 不存在且打包失败，请先执行 mvn -DskipTests package",
      });
      console.error(tail((build.stdout || "") + (build.stderr || "")));
      return;
    }
  }

  const port = 18099;
  const child = spawn(
    "java",
    [
      "-jar",
      jar,
      "--spring.profiles.active=local",
      "--spring.flyway.enabled=false",
      "--ontos.agent.enabled=false",
      "--ontos.manifest.startup-load=false",
      `--server.port=${port}`,
    ],
    { stdio: "ignore" },
  );

  try {
    for (let attempt = 0; attempt < 25; attempt += 1) {
      await new Promise((done) => setTimeout(done, 1000));
      if (child.exitCode !== null) break;
      try {
        const response = await fetch(`http://127.0.0.1:${port}/actuator/health`, {
          signal: AbortSignal.timeout(1500),
        });
        const body = await response.json();
        results.push({
          name,
          ok: true,
          detail: `应用已启动（health=${body.status ?? "unknown"}，无数据库模式）`,
        });
        return;
      } catch {
        // 端口尚未监听，继续轮询
      }
    }
    results.push({ name, ok: false, detail: "启动超时或端口未监听" });
  } finally {
    child.kill();
  }
}

function report() {
  console.log("\n基线自检报告");
  for (const item of results) {
    const state = item.ok ? "PASS" : item.skipped ? "SKIP" : "FAIL";
    console.log(`  [${state}] ${item.name}${item.detail ? ` — ${item.detail}` : ""}`);
  }
  const passed = results.filter((item) => item.ok).length;
  const failed = results.filter((item) => !item.ok && !item.skipped).length;
  const skipped = results.filter((item) => item.skipped).length;
  console.log(`结果：${failed === 0 && skipped === 0 ? "PASS" : "NOT GREEN"}`
    + `（${passed} PASS / ${failed} FAIL / ${skipped} SKIP，跳过不算通过）`);
  return failed === 0 && skipped === 0 ? 0 : 1;
}

// package 同时跑测试并产出最新 jar，供后面的启动探活使用，避免 Maven 执行两次
check("后端 mvn package（含测试）", "ontos", "mvn -B package");
check("前端 pnpm check", "ontos-ui", "pnpm check");
check("docs 治理检查", ".", "node docs/00-governance/check-docs.mjs");
await bootProbe();

process.exit(report());
