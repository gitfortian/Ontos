import assert from "node:assert/strict";
import { test } from "node:test";
import {
  API_SUCCESS_CODE,
  extractErrorMessage,
  extractUnknownErrorMessage,
  isApiResponse,
  isSuccessfulResponse,
  isUnauthenticatedResponse,
} from "./response.ts";

test("success code matches the backend CommonResult contract", () => {
  assert.equal(API_SUCCESS_CODE, 0);
  assert.equal(isSuccessfulResponse({ code: 0 }), true);
  assert.equal(isSuccessfulResponse({ code: 200 }), false);
  assert.equal(isSuccessfulResponse(null), false);
});

test("recognises the response envelope without trusting the data shape", () => {
  assert.equal(isApiResponse({ code: 0, data: null }), true);
  assert.equal(isApiResponse({ code: "0", data: null }), false);
  assert.equal(isApiResponse(null), false);
});

test("prefers msg, falls back to message, and hides HTML error pages", () => {
  assert.equal(extractErrorMessage({ msg: "清单不存在" }), "清单不存在");
  assert.equal(extractErrorMessage({ message: "超时" }, "兜底"), "超时");
  assert.equal(extractErrorMessage({ msg: "<html>oops</html>" }, "兜底"), "兜底");
  assert.equal(extractUnknownErrorMessage({ error: "校验失败" }), "校验失败");
  assert.equal(extractUnknownErrorMessage(undefined, "请求失败"), "请求失败");
});

test("overlong diagnostics are shortened before they reach the UI", () => {
  const longMessage = "x".repeat(400);
  const message = extractErrorMessage({ msg: longMessage });
  assert.ok(message.length <= 240);
  assert.ok(message.endsWith("…"));
});

test("unauthenticated codes and messages are recognised", () => {
  assert.equal(isUnauthenticatedResponse({ code: 401 }), true);
  assert.equal(isUnauthenticatedResponse({ code: 0, msg: "SESSION_EXPIRED" }), true);
  assert.equal(isUnauthenticatedResponse({ code: 0, msg: "发布清单不存在" }), false);
});
