import assert from "node:assert/strict";
import { test } from "node:test";

import {
  parseRunEvent,
  reduceStep,
  resolveTurnForEvent,
  stepOf,
  terminalOf,
  toolOf,
  toolStep,
} from "./events.ts";

const stepEvent = (seq, stepKey, status, extra = {}) => ({
  seq,
  type: "CUSTOM",
  payload: JSON.stringify({
    name: "ontos.step",
    value: { stepKey, title: "匹配已发布资产", status, ...extra },
  }),
});

test("normalizes transport payload into replay shape", () => {
  const event = parseRunEvent({ seq: 3, type: "TEXT_MESSAGE_CONTENT", payload: { delta: "hi" } });
  assert.equal(event.seq, 3);
  assert.equal(event.type, "TEXT_MESSAGE_CONTENT");
  assert.equal(typeof event.payload, "string");
  assert.deepEqual(JSON.parse(event.payload), { delta: "hi" });
});

test("extracts step payload and rejects unknown custom names", () => {
  const step = stepOf(
    parseRunEvent(
      stepEvent(4, "retrieve", "done", { detail: "命中 2 项：成交金额、大区", elapsedMs: 8 }),
    ),
  );
  assert.equal(step.stepKey, "retrieve");
  assert.equal(step.status, "done");
  assert.equal(step.detail, "命中 2 项：成交金额、大区");
  assert.equal(step.elapsedMs, 8);

  const terminalLike = parseRunEvent({
    seq: 5,
    type: "CUSTOM",
    payload: JSON.stringify({ name: "ontos.terminal", value: { terminal: "query" } }),
  });
  assert.equal(stepOf(terminalLike), null);
});

test("extracts terminal payload", () => {
  const terminal = terminalOf(
    parseRunEvent({
      seq: 9,
      type: "CUSTOM",
      payload: JSON.stringify({
        name: "ontos.terminal",
        value: { terminal: "query", body: { rowCount: 5, columns: [] } },
      }),
    }),
  );
  assert.equal(terminal.terminal, "query");
  assert.equal(terminal.body.rowCount, 5);
});

test("running then done updates the same entry instead of adding a row", () => {
  const running = stepOf(parseRunEvent(stepEvent(1, "execute", "running")));
  const done = stepOf(parseRunEvent(stepEvent(2, "execute", "done", { detail: "返回 5 行" })));

  let entries = reduceStep([], 1, running);
  entries = reduceStep(entries, 2, done);

  assert.equal(entries.length, 1);
  assert.equal(entries[0].status, "done");
  assert.equal(entries[0].detail, "返回 5 行");
  assert.equal(entries[0].attempt, 1);
  assert.equal(entries[0].seq, 2);
});

test("a re-run after failure keeps history and marks the attempt", () => {
  const failed = stepOf(
    parseRunEvent(stepEvent(3, "validate", "failed", { detail: "契约未通过" })),
  );
  const retry = stepOf(parseRunEvent(stepEvent(6, "validate", "done", { detail: "通过" })));

  let entries = reduceStep([], 3, failed);
  entries = reduceStep(entries, 6, retry);

  assert.equal(entries.length, 2);
  assert.equal(entries[0].status, "failed");
  assert.equal(entries[0].attempt, 1);
  assert.equal(entries[1].status, "done");
  assert.equal(entries[1].attempt, 2);
});

test("tool start and end merge into one entry keeping both summaries", () => {
  const start = toolOf(
    parseRunEvent({
      seq: 7,
      type: "TOOL_CALL_START",
      payload: JSON.stringify({
        toolName: "execute_query",
        argsSummary: ["指标 成交金额", "维度 大区"],
      }),
    }),
  );
  const end = toolOf(
    parseRunEvent({
      seq: 8,
      type: "TOOL_CALL_END",
      payload: JSON.stringify({
        toolName: "execute_query",
        status: "succeeded",
        resultSummary: ["返回 5 行", "未截断"],
        elapsedMs: 128,
      }),
    }),
  );

  let entries = reduceStep([], 7, toolStep(start, "start"));
  entries = reduceStep(entries, 8, toolStep(end, "end"));

  assert.equal(entries.length, 1);
  assert.equal(entries[0].stepKey, "tool:execute_query");
  assert.equal(entries[0].status, "done");
  assert.deepEqual(entries[0].argsSummary, ["指标 成交金额", "维度 大区"]);
  assert.deepEqual(entries[0].resultSummary, ["返回 5 行", "未截断"]);
  assert.equal(entries[0].elapsedMs, 128);
  assert.equal(entries[0].isTool, true);
});

test("closed tool is shown as skipped, not as a success", () => {
  const end = toolOf(
    parseRunEvent({
      seq: 9,
      type: "TOOL_CALL_END",
      payload: JSON.stringify({
        toolName: "execute_query",
        status: "closed",
        resultSummary: ["审批未放行"],
      }),
    }),
  );
  const entry = reduceStep([], 9, toolStep(end, "end"))[0];
  assert.equal(entry.status, "skipped");
  assert.equal(entry.detail, "审批未放行");
});

test("tool summaries survive via CUSTOM ontos.tool (AG-UI enforce stripped TOOL_CALL_*)", () => {
  const start = toolOf(
    parseRunEvent({
      seq: 11,
      type: "CUSTOM",
      payload: JSON.stringify({
        name: "ontos.tool",
        value: {
          phase: "start",
          toolName: "execute_query",
          argsSummary: ["指标 GMV", "维度 渠道"],
        },
      }),
    }),
  );
  const end = toolOf(
    parseRunEvent({
      seq: 12,
      type: "CUSTOM",
      payload: JSON.stringify({
        name: "ontos.tool",
        value: {
          phase: "end",
          toolName: "execute_query",
          status: "succeeded",
          resultSummary: ["返回 2 行", "未截断"],
          elapsedMs: 42,
        },
      }),
    }),
  );
  assert.equal(start.phase, "start");
  assert.equal(end.phase, "end");
  assert.deepEqual(start.argsSummary, ["指标 GMV", "维度 渠道"]);
  assert.deepEqual(end.resultSummary, ["返回 2 行", "未截断"]);

  let entries = reduceStep([], 11, toolStep(start, start.phase));
  entries = reduceStep(entries, 12, toolStep(end, end.phase));
  assert.equal(entries.length, 1);
  assert.equal(entries[0].status, "done");
  assert.equal(entries[0].elapsedMs, 42);
});

test("resolveTurnForEvent routes by runId and drops stopped-turn leftovers", () => {
  const runs = [
    { id: "t1", runId: "run-1", running: false },
    { id: "t2", runId: "run-2", running: true },
  ];
  assert.equal(resolveTurnForEvent(runs, "run-2").id, "t2");
  // run-1 已停止：迟到事件不得串到 t2
  assert.equal(resolveTurnForEvent(runs, "run-1"), null);
  // 无 runId 的事件（CUSTOM 被剥离）→ 最新运行中轮
  assert.equal(resolveTurnForEvent(runs, null).id, "t2");
  assert.equal(resolveTurnForEvent(runs, "run-unknown"), null);
  assert.equal(resolveTurnForEvent([], "run-2"), null);
});
