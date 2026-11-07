import assert from "node:assert/strict";
import { test } from "node:test";
import { redactAguiEvent } from "./redact.ts";

const runStarted = {
  type: "RUN_STARTED",
  threadId: "t-1",
  runId: "r-1",
  input: {
    threadId: "t-1",
    runId: "r-1",
    messages: [
      { id: "m1", role: "user", content: "华东区上个月的 GMV 是多少" },
      { id: "m2", role: "assistant", content: "{}" },
    ],
    tools: [],
    state: {},
  },
};

test("strips user content from RUN_STARTED.input", () => {
  const redacted = redactAguiEvent(runStarted);
  const serialized = JSON.stringify(redacted.input);
  assert.equal(serialized.includes("华东区上个月的 GMV 是多少"), false);
  assert.equal(serialized.includes("m1"), false);
});

test("keeps diagnosable non-sensitive fields", () => {
  const redacted = redactAguiEvent(runStarted);
  const input = redacted.input;
  assert.equal(input.threadId, "t-1");
  assert.equal(input.runId, "r-1");
  assert.equal(input.messageCount, 2);
  assert.equal(input.lastMessageLength, "{}".length);
});

test("leaves streaming events untouched", () => {
  const delta = { type: "TEXT_MESSAGE_CONTENT", delta: "华东区" };
  assert.equal(redactAguiEvent(delta), delta);
});

test("tolerates RUN_STARTED without input", () => {
  const event = { type: "RUN_STARTED", threadId: "t" };
  assert.equal(redactAguiEvent(event), event);
});

test("does not throw on malformed messages", () => {
  const event = { type: "RUN_STARTED", input: { messages: "not-an-array" } };
  const redacted = redactAguiEvent(event);
  const input = redacted.input;
  assert.equal(input.messageCount, 0);
  assert.equal(input.lastMessageLength, 0);
});
