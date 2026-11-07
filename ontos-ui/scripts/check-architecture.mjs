import { existsSync, readFileSync, readdirSync } from "node:fs";
import { extname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { checkArchitectureImports } from "./architecture-imports.mjs";

const root = fileURLToPath(new URL("..", import.meta.url));
const violations = [];

const toRelativePath = (path) => relative(root, path).split(sep).join("/");

const fail = (message) => {
  violations.push(message);
};

const forbiddenDirectories = [
  "src",
  "public",
  "types",
  "mock",
  "apps/web/src",
  "apps/web/pages",
  "apps/web/shared",
];

for (const path of forbiddenDirectories) {
  if (existsSync(join(root, path))) {
    fail(`forbidden legacy directory exists: ${path}`);
  }
}

const requiredFiles = [
  "FRONTEND_RULES.md",
  "apps/web/main.tsx",
  "apps/web/app/App.tsx",
  "apps/web/app/router/AppRouter.tsx",
  "apps/web/app/layout/AppLayout.tsx",
  "apps/web/app/layout/navigation.ts",
  "apps/web/app/styles/global.css",
  "apps/web/service/http/request.ts",
  "apps/web/service/http/response.ts",
  "apps/web/service/http/HttpUtils.ts",
  "packages/ontos-ui/src/index.ts",
  "packages/ontos-ui/src/styles.css",
];

for (const path of requiredFiles) {
  if (!existsSync(join(root, path))) {
    fail(`required baseline file is missing: ${path}`);
  }
}

const packageRoot = join(root, "packages");
const allowedPackages = new Set(["ontos-ui"]);
if (existsSync(packageRoot)) {
  for (const entry of readdirSync(packageRoot, { withFileTypes: true })) {
    if (entry.isDirectory() && !allowedPackages.has(entry.name)) {
      fail(`unexpected workspace package: packages/${entry.name}`);
    }
  }
}

const forbiddenDependencies = new Set([
  "@ant-design/icons",
  "@umijs/max",
  "antd",
  "axios",
  "umi-request",
]);

const rootPackage = JSON.parse(readFileSync(join(root, "package.json"), "utf8"));
const rootRuntimeDependencies = Object.keys(rootPackage.dependencies ?? {});
if (rootRuntimeDependencies.length > 0) {
  fail(`workspace root must not own runtime dependencies: ${rootRuntimeDependencies.join(", ")}`);
}

for (const dependency of Object.keys(rootPackage.devDependencies ?? {})) {
  if (forbiddenDependencies.has(dependency)) {
    fail(`workspace root declares forbidden dependency: ${dependency}`);
  }
}

const sourceExtensions = new Set([".js", ".jsx", ".mjs", ".ts", ".tsx", ".json"]);
const ignoredDirectoryNames = new Set(["assets", "dist", "node_modules", "public"]);
const files = [];

const walk = (directory) => {
  if (!existsSync(directory)) return;
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.isDirectory() && ignoredDirectoryNames.has(entry.name)) continue;
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      walk(path);
      continue;
    }
    if (sourceExtensions.has(extname(entry.name))) files.push(path);
  }
};

walk(join(root, "apps", "web"));
walk(join(root, "packages"));
files.push(join(root, "tsconfig.json"));

const directFetchPattern = /\b(?:globalThis\.|window\.)?fetch\s*\(/;
const baseUiImportPattern = /(?:from\s+|import\s*\()\s*["']@base-ui\/react/;

for (const path of files) {
  const relativePath = toRelativePath(path);
  const content = readFileSync(path, "utf8");

  if (relativePath.endsWith("package.json")) {
    const packageJson = JSON.parse(content);
    const dependencies = {
      ...packageJson.dependencies,
      ...packageJson.devDependencies,
      ...packageJson.peerDependencies,
    };
    for (const dependency of Object.keys(dependencies)) {
      if (forbiddenDependencies.has(dependency)) {
        fail(`${relativePath} declares forbidden dependency: ${dependency}`);
      }
    }
  }

  if ([".js", ".jsx", ".mjs", ".ts", ".tsx"].includes(extname(path))) {
    for (const message of checkArchitectureImports(relativePath, content, root)) fail(message);
  }

  if (relativePath !== "apps/web/service/http/request.ts" && directFetchPattern.test(content)) {
    fail(`${relativePath} calls fetch directly outside service/http/request.ts`);
  }

  if (baseUiImportPattern.test(content)) {
    fail(`${relativePath} imports @base-ui/react; the source component library is not allowed`);
  }

  if (content.includes("--yak-")) {
    fail(`${relativePath} references the source design system tokens`);
  }

  if (content.includes("@yak-ops/")) {
    fail(`${relativePath} references the source project package name`);
  }
}

if (violations.length > 0) {
  console.error("Frontend architecture check failed:");
  for (const violation of violations) console.error(`- ${violation}`);
  process.exit(1);
}

console.log("Frontend architecture check passed.");
