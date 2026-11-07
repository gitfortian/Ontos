import { dirname, resolve, sep } from "node:path";
import ts from "typescript";

function moduleSpecifiers(filePath, content) {
  const source = ts.createSourceFile(filePath, content, ts.ScriptTarget.Latest, true);
  const specifiers = [];
  function visit(node) {
    if (
      (ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) &&
      node.moduleSpecifier &&
      ts.isStringLiteral(node.moduleSpecifier)
    ) {
      specifiers.push(node.moduleSpecifier.text);
    }
    if (
      ts.isCallExpression(node) &&
      (node.expression.kind === ts.SyntaxKind.ImportKeyword ||
        (ts.isIdentifier(node.expression) && node.expression.text === "require")) &&
      node.arguments.length === 1 &&
      ts.isStringLiteral(node.arguments[0])
    ) {
      specifiers.push(node.arguments[0].text);
    }
    ts.forEachChild(node, visit);
  }
  visit(source);
  return specifiers;
}

const inside = (candidate, owner) => candidate === owner || candidate.startsWith(owner + sep);

export function checkArchitectureImports(relativePath, content, root) {
  const source = resolve(root, relativePath);
  const app = resolve(root, "apps/web/app");
  const service = resolve(root, "apps/web/service");
  const http = resolve(root, "apps/web/service/http");
  const ui = resolve(root, "packages/ontos-ui");
  const router = resolve(app, "router");
  const layout = resolve(app, "layout");
  const shellEntries = new Set([
    resolve(layout, "AppLayout"),
    resolve(layout, "navigation"),
    resolve(layout, "navigation-path"),
  ]);
  const featureRoots = ["ask", "ontology", "approvals", "audit"].map((name) => resolve(app, name));
  const publicPageEntries = new Set([
    resolve(app, "ask/page"),
    resolve(app, "ontology/manifests/page"),
    resolve(app, "ontology/assets/page"),
    resolve(app, "ontology/diff/page"),
    resolve(app, "ontology/records/page"),
    resolve(app, "approvals/page"),
    resolve(app, "audit/page"),
  ]);
  const violations = [];

  for (const specifier of moduleSpecifiers(relativePath, content)) {
    let target;
    if (specifier.startsWith("@/")) {
      target = resolve(root, "apps/web", specifier.slice(2));
    } else if (specifier.startsWith(".")) {
      target = resolve(dirname(source), specifier);
    } else {
      continue;
    }

    if (inside(target, layout) && !inside(source, layout)) {
      const allowed = inside(source, router) && shellEntries.has(target);
      if (!allowed) {
        violations.push(relativePath + " imports a private App Shell module: " + specifier);
      }
    }
    if (inside(source, layout) && inside(target, app) && !inside(target, layout)) {
      violations.push(relativePath + " imports a feature into App Shell: " + specifier);
    }

    for (const featureRoot of featureRoots) {
      if (inside(target, featureRoot) && !inside(source, featureRoot)) {
        const allowed = inside(source, router) && publicPageEntries.has(target);
        if (!allowed) {
          violations.push(relativePath + " imports a private feature: " + specifier);
        }
      }
    }

    if (inside(source, service) && inside(target, app)) {
      violations.push(relativePath + " imports App code: " + specifier);
    }
    if (inside(source, app) && inside(target, http)) {
      violations.push(relativePath + " bypasses domain Service: " + specifier);
    }
    if (inside(source, ui) && (inside(target, app) || inside(target, service))) {
      violations.push(relativePath + " imports product code into Ontos UI: " + specifier);
    }
  }

  return violations;
}
