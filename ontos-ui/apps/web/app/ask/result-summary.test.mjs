import assert from "node:assert/strict";
import { test } from "node:test";

import { buildBars, formatCellValue, orderColumnsForDisplay, summarizeResult } from "./result-summary.ts";

const columns = [
  { key: "dim.region", label: "大区", role: "dimension", numeric: false },
  { key: "value", label: "成交金额", role: "metric", numeric: true },
];

const rows = [
  { "dim.region": "华东", value: 486.2 },
  { "dim.region": "华南", value: 312.6 },
];

test("summarizes a single metric column with its business label", () => {
  const summary = summarizeResult(rows, columns);
  assert.equal(summary.metricLabel, "成交金额");
  assert.deepEqual(summary.dimensionLabels, ["大区"]);
  assert.equal(summary.rowCount, 2);
  assert.equal(Math.round(summary.total * 10) / 10, 798.8);
});

test("does not invent a total when there are two metric columns", () => {
  const twoMetrics = [
    ...columns,
    { key: "value2", label: "订单量", role: "metric", numeric: true },
  ];
  const summary = summarizeResult(rows, twoMetrics);
  assert.equal(summary.total, null);
  assert.equal(summary.metricLabel, "成交金额 / 订单量");
});

test("returns no total for empty or non-numeric data instead of zero", () => {
  const summary = summarizeResult([], columns);
  assert.equal(summary.total, null);
  assert.equal(summary.rowCount, 0);

  const nonNumeric = summarizeResult([{ "dim.region": "华东", value: "?" }], columns);
  assert.equal(nonNumeric.total, null);
});

test("builds bars only when exactly one dimension and one metric exist", () => {
  const built = buildBars(rows, columns);
  assert.equal(built.truncated, false);
  assert.deepEqual(built.bars, [
    { label: "华东", value: 486.2 },
    { label: "华南", value: 312.6 },
  ]);

  const noDimension = buildBars(rows, [columns[1]]);
  assert.deepEqual(noDimension.bars, []);
});

test("truncates bars past the limit and says so", () => {
  const many = Array.from({ length: 15 }, (_, index) => ({
    "dim.region": `区-${index + 1}`,
    value: index + 1,
  }));
  const built = buildBars(many, columns, 12);
  assert.equal(built.bars.length, 12);
  assert.equal(built.truncated, true);
});

test("formats cells without binary float noise", () => {
  assert.equal(formatCellValue(798.8000000001), "798.8");
  assert.equal(formatCellValue(1284), "1,284");
  assert.equal(formatCellValue(null), "—");
  assert.equal(formatCellValue(true), "是");
  assert.equal(formatCellValue("华东"), "华东");
});

test("shows dimensions before metrics without reordering inside a group", () => {
  const ordered = orderColumnsForDisplay([
    { key: "value", label: "成交金额", role: "metric", numeric: true },
    { key: "dim.region", label: "大区", role: "dimension", numeric: false },
    { key: "dim.channel", label: "渠道", role: "dimension", numeric: false },
    { key: "__identity", label: "标识", role: "other", numeric: false },
  ]);
  assert.deepEqual(
    ordered.map((column) => column.key),
    ["dim.region", "dim.channel", "value", "__identity"],
  );
});
