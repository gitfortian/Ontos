import assert from "node:assert/strict";
import { test } from "node:test";

import {
  buildChartData,
  buildInsights,
  formatCellValue,
  orderColumnsForDisplay,
  summarizeResult,
  toCsv,
} from "./result-summary.ts";

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

test("builds chart data with raw labels for drilldown, or null without a pair", () => {
  const built = buildChartData(rows, columns);
  assert.equal(built.truncated, false);
  assert.equal(built.dimensionLabel, "大区");
  assert.equal(built.metricLabel, "成交金额");
  assert.deepEqual(built.points, [
    { label: "华东", rawLabel: "华东", value: 486.2 },
    { label: "华南", rawLabel: "华南", value: 312.6 },
  ]);

  assert.equal(buildChartData(rows, [columns[1]]), null);
  assert.equal(buildChartData(rows, [{ ...columns[0], role: "metric", numeric: true }]), null);
});

test("truncates chart points past the limit and says so", () => {
  const many = Array.from({ length: 15 }, (_, index) => ({
    "dim.region": `区-${index + 1}`,
    value: index + 1,
  }));
  const built = buildChartData(many, columns, 12);
  assert.equal(built.points.length, 12);
  assert.equal(built.truncated, true);
});

test("builds deterministic insights: total, top item with share, no growth rate", () => {
  const insights = buildInsights(rows, columns);
  assert.equal(insights.length, 2);
  assert.match(insights[0], /共 2 行，成交金额合计 798\.8/);
  assert.match(insights[1], /「华东」最高（486\.2，占 60\.9%）/);

  const single = buildInsights([{ "dim.region": "华东", value: 486.2 }], columns);
  assert.deepEqual(single, ["共 1 行，成交金额合计 486.2", "「华东」成交金额为 486.2"]);

  // 无对照数据不出增长率：洞察集合里不允许出现「增长」「环比」字样
  assert.ok(!insights.join("").includes("增长"));
  // 缺指标或缺行时为空（不出空话）
  assert.deepEqual(buildInsights(rows, [columns[0]]), []);
  assert.deepEqual(buildInsights([], columns), []);
});

test("exports csv with bom, original column order and proper escaping", () => {
  const csv = toCsv(rows, columns);
  assert.ok(csv.startsWith("\uFEFF"));
  assert.equal(csv, "\uFEFF大区,成交金额\r\n华东,486.2\r\n华南,312.6");

  const tricky = toCsv([{ "dim.region": '含"引号",逗号', value: 1 }], columns);
  assert.ok(tricky.includes('"含""引号"",逗号"'));
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
