import assert from "node:assert/strict";
import { test } from "node:test";
import { isNavigationActive } from "./navigation-path.ts";

test("exact route activates its navigation entry", () => {
  assert.equal(isNavigationActive("/ask", "/ask"), true);
  assert.equal(isNavigationActive("/approvals", "/approvals"), true);
});

test("nested detail routes keep the parent entry active", () => {
  assert.equal(isNavigationActive("/ontology/manifests", "/ontology"), true);
});

test("similar prefixes and unrelated routes stay inactive", () => {
  assert.equal(isNavigationActive("/ask-archive", "/ask"), false);
  assert.equal(isNavigationActive("/audit", "/ask"), false);
  assert.equal(isNavigationActive("/", "/ask"), false);
});
