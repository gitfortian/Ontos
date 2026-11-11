import assert from "node:assert/strict";
import { test } from "node:test";
import { STATUS_LABEL, buildFailureTimeline, nextStatusActions } from "./failure-lifecycle.ts";

const ALL_STATUSES = ["open", "triaged", "fixed", "regressed", "ignored"];

test("every status has a label and only open starts triage", () => {
  for (const status of ALL_STATUSES) {
    assert.ok(STATUS_LABEL[status], `缺少状态文案: ${status}`);
  }
  assert.deepEqual(nextStatusActions("open"), [{ to: "triaged", label: "开始分诊" }]);
  assert.deepEqual(nextStatusActions("fixed"), [{ to: "triaged", label: "重新分诊" }]);
});

test("regressed is never offered as a direct transition target", () => {
  for (const status of ALL_STATUSES) {
    const targets = nextStatusActions(status).map((action) => action.to);
    assert.ok(!targets.includes("regressed"), `${status} 不应直接提供 regressed`);
  }
});

test("unknown status yields no actions (fail-closed)", () => {
  assert.deepEqual(nextStatusActions("bogus"), []);
  assert.deepEqual(nextStatusActions(""), []);
});

test("timeline merges status and classification events in reverse chronological order", () => {
  const timeline = buildFailureTimeline(
    [
      {
        id: 2,
        fromStatus: "triaged",
        toStatus: "fixed",
        actor: "alice",
        note: null,
        createdAt: "2026-11-11 10:02:00",
      },
      {
        id: 1,
        fromStatus: "open",
        toStatus: "triaged",
        actor: "alice",
        note: "开始分诊",
        createdAt: "2026-11-11 10:01:00",
      },
    ],
    [
      {
        id: 7,
        category: "SCENARIO_SCOPE",
        actor: "bob",
        note: "scope 越界",
        createdAt: "2026-11-11 10:03:00",
      },
    ],
    (code) => (code === "SCENARIO_SCOPE" ? "Scenario scope 越界" : code),
  );

  assert.deepEqual(
    timeline.map((entry) => [entry.kind, entry.text]),
    [
      ["分类", "Scenario scope 越界"],
      ["状态", "triaged → fixed"],
      ["状态", "open → triaged"],
    ],
  );
  assert.equal(timeline[2].note, "开始分诊");
  assert.equal(timeline[0].key, "category-7");
});

test("missing history renders an empty timeline instead of throwing", () => {
  assert.deepEqual(
    buildFailureTimeline(undefined, undefined, (code) => code),
    [],
  );
});
