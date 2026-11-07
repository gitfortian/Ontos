import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const read = (path) => readFileSync(new URL("../" + path, import.meta.url), "utf8");
const sharedTokens = read("packages/ontos-ui/src/styles.css");
const appEntry = read("apps/web/main.tsx");

/** 取出某个选择器块内的声明文本，避免浅色/深色规则互相污染断言 */
const blockOf = (css, selector) => {
  const start = css.indexOf(selector);
  assert.notEqual(start, -1, `Missing ${selector} block`);
  const open = css.indexOf("{", start);
  let depth = 0;
  for (let index = open; index < css.length; index += 1) {
    if (css[index] === "{") depth += 1;
    if (css[index] === "}") {
      depth -= 1;
      if (depth === 0) return css.slice(open + 1, index);
    }
  }
  throw new Error(`Unclosed ${selector} block`);
};

const lightBlock = blockOf(sharedTokens, ":root");
const darkBlock = blockOf(sharedTokens, ".dark");

// shadcn/ui semantic tokens: one light definition plus a dark override.
assert.match(lightBlock, /--primary:\s*#[0-9a-fA-F]{6};/);
assert.equal(
  [...lightBlock.matchAll(/(^|[^-\w])--primary\s*:/g)].length,
  1,
  "Product theme has one light primary color configuration",
);
assert.match(darkBlock, /--primary\s*:/, "Dark theme overrides the primary color");
assert.match(lightBlock, /--primary-hover:\s*color-mix\(/, "primary-hover derives from primary");
assert.match(lightBlock, /--primary-subtle:\s*color-mix\(/, "primary-subtle derives from primary");

assert.match(appEntry, /import "\.\/app\/styles\/global\.css";/);
assert.doesNotMatch(
  appEntry,
  /themes\/index\.css|theme\/index\.css/,
  "No source theme stylesheets",
);

// Source design system must not leak back into the selected stack.
assert.doesNotMatch(sharedTokens, /--yak-/);
assert.doesNotMatch(sharedTokens, /#0033ff/);
