import assert from "node:assert/strict";
import { test } from "node:test";
import { checkArchitectureImports } from "./architecture-imports.mjs";

const root = process.cwd();
const check = (file, source) => checkArchitectureImports(file, source, root);

test("service cannot import App code via aliases or relative re-exports", () => {
  assert.match(
    check("apps/web/service/query/index.ts", 'import x from "@/app/ask/page";').join("\n"),
    /imports App code/,
  );
  assert.match(
    check("apps/web/service/query/index.ts", 'export * from "../../app/ask/page";').join("\n"),
    /imports App code/,
  );
});

test("App cannot bypass the domain service through imports", () => {
  assert.match(
    check("apps/web/app/ask/page.tsx", 'const http = import("@/service/http/request");').join("\n"),
    /bypasses domain Service/,
  );
  assert.match(
    check("apps/web/app/ask/page.tsx", 'import "../../service/http/HttpUtils";').join("\n"),
    /bypasses domain Service/,
  );
});

test("Ontos UI cannot reach product code", () => {
  assert.match(
    check("packages/ontos-ui/src/button/Button.tsx", 'import x from "@/service/query";').join("\n"),
    /imports product code into Ontos UI/,
  );
});

test("router imports public page entries but not private feature files", () => {
  const router = "apps/web/app/router/AppRouter.tsx";
  assert.deepEqual(check(router, 'import { AskPage } from "@/app/ask/page";'), []);
  assert.deepEqual(
    check(router, 'import { ManifestsPage } from "@/app/ontology/manifests/page";'),
    [],
  );
  assert.match(
    check(router, 'import { AguiPanel } from "@/app/ask/agui-panel";').join("\n"),
    /imports a private feature/,
  );
});

test("other domains cannot deep import a private feature", () => {
  assert.match(
    check("apps/web/app/audit/page.tsx", 'import { AguiPanel } from "@/app/ask/agui-panel";').join(
      "\n",
    ),
    /imports a private feature/,
  );
});

test("App Shell modules stay private to the shell and the router", () => {
  assert.deepEqual(
    check("apps/web/app/router/AppRouter.tsx", 'import AppLayout from "@/app/layout/AppLayout";'),
    [],
  );
  assert.match(
    check(
      "apps/web/app/ask/page.tsx",
      'import { NAVIGATION } from "@/app/layout/navigation";',
    ).join("\n"),
    /imports a private App Shell module/,
  );
});

test("feature-local imports and ordinary UI imports are allowed", () => {
  assert.deepEqual(
    check("apps/web/app/ask/page.tsx", 'import { AguiPanel } from "./agui-panel";'),
    [],
  );
  assert.deepEqual(
    check("apps/web/app/ask/page.tsx", 'import { PageHeader } from "@ontos/ui";'),
    [],
  );
});

test("strings and comments are not treated as module imports", () => {
  assert.deepEqual(
    check(
      "apps/web/service/query/index.ts",
      '// import "@/app/ask/page";\nconst text = \'import "@/app/ask/page"\';',
    ),
    [],
  );
});
