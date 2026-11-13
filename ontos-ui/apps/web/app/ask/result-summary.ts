import type { AskColumnMeta } from "@/service/ask";

/** 结果摘要：只从服务端下发的列元数据与行数据计算，不做任何推测。 */
export interface ResultSummary {
  /** 唯一指标列的业务名；有多列或无指标列时为 null（不猜）。 */
  metricLabel: string | null;
  /** 维度列的业务名，按结果集顺序。 */
  dimensionLabels: string[];
  /** 唯一指标列求和；不满足条件时为 null。 */
  total: number | null;
  rowCount: number;
}

export const summarizeResult = (
  rows: Array<Record<string, unknown>>,
  columns: AskColumnMeta[],
): ResultSummary => {
  const metricColumns = columns.filter((column) => column.role === "metric" && column.numeric);
  const dimensionLabels = columns
    .filter((column) => column.role === "dimension")
    .map((column) => column.label);
  const rowCount = rows.length;

  if (metricColumns.length !== 1) {
    return {
      metricLabel:
        metricColumns.length === 0 ? null : metricColumns.map((column) => column.label).join(" / "),
      dimensionLabels,
      total: null,
      rowCount,
    };
  }

  const key = metricColumns[0].key;
  let sum = 0;
  let counted = 0;
  for (const row of rows) {
    const value = row[key];
    if (typeof value === "number" && Number.isFinite(value)) {
      sum += value;
      counted += 1;
    }
  }

  return {
    metricLabel: metricColumns[0].label,
    dimensionLabels,
    total: counted === 0 ? null : sum,
    rowCount,
  };
};

/** 图表数据点：rawLabel 保留原始维度值（下钻拼话术用），label 是展示值。 */
export interface ChartPoint {
  label: string;
  rawLabel: string;
  value: number;
}

export interface ChartData {
  dimensionKey: string;
  dimensionLabel: string;
  metricKey: string;
  metricLabel: string;
  points: ChartPoint[];
  truncated: boolean;
}

/**
 * 图表数据：只接受「一个维度 + 一个指标」，超过上限截断并如实返回 truncated。
 * 维度值可能是日期、编码或文本——不做类型猜测，统一按分类轴呈现。
 */
export const buildChartData = (
  rows: Array<Record<string, unknown>>,
  columns: AskColumnMeta[],
  limit = 12,
): ChartData | null => {
  const dimension = columns.find((column) => column.role === "dimension");
  const metric = columns.find((column) => column.role === "metric" && column.numeric);
  if (!dimension || !metric) {
    return null;
  }
  const points: ChartPoint[] = [];
  for (const row of rows) {
    const value = row[metric.key];
    if (typeof value !== "number" || !Number.isFinite(value)) {
      continue;
    }
    const raw = row[dimension.key];
    const rawLabel = raw == null ? "—" : String(raw);
    points.push({ label: rawLabel, rawLabel, value });
  }
  const data: ChartData = {
    dimensionKey: dimension.key,
    dimensionLabel: dimension.label,
    metricKey: metric.key,
    metricLabel: metric.label,
    points,
    truncated: points.length > limit,
  };
  if (data.truncated) {
    data.points = points.slice(0, limit);
  }
  return data;
};

/**
 * 确定性洞察（InsightSummary 数据源）：全部由 rows 实算——合计、最高项及占比。
 * 没有对照数据就不出增长率；没有任何可算结论就返回空数组（不出空话）。
 */
export const buildInsights = (
  rows: Array<Record<string, unknown>>,
  columns: AskColumnMeta[],
): string[] => {
  const metric = columns.find((column) => column.role === "metric" && column.numeric);
  if (!metric || rows.length === 0) {
    return [];
  }
  const dimension = columns.find((column) => column.role === "dimension");
  const numericRows = rows
    .map((row) => ({ row, value: row[metric.key] }))
    .filter(
      (item): item is { row: Record<string, unknown>; value: number } =>
        typeof item.value === "number" && Number.isFinite(item.value),
    );
  if (numericRows.length === 0) {
    return [];
  }
  const total = numericRows.reduce((sum, item) => sum + item.value, 0);
  const insights: string[] = [
    `共 ${numericRows.length} 行，${metric.label}合计 ${formatCellValue(total)}`,
  ];

  const dimensionValue = (row: Record<string, unknown>): string => {
    if (!dimension) {
      return "";
    }
    const raw = row[dimension.key];
    return raw == null ? "—" : String(raw);
  };

  if (numericRows.length === 1) {
    const only = numericRows[0];
    const subject = dimensionValue(only.row);
    insights.push(
      subject
        ? `「${subject}」${metric.label}为 ${formatCellValue(only.value)}`
        : `${metric.label}为 ${formatCellValue(only.value)}`,
    );
    return insights;
  }

  if (dimension && total > 0) {
    const top = numericRows.reduce((best, item) => (item.value > best.value ? item : best));
    const share = (top.value / total) * 100;
    insights.push(
      `「${dimensionValue(top.row)}」最高（${formatCellValue(top.value)}，占 ${share.toFixed(1)}%）`,
    );
  }
  return insights;
};

/** 数值展示：保留两位小数，整数不补零；非数值原样转字符串。 */
export const formatCellValue = (value: unknown): string => {
  if (value === null || value === undefined) {
    return "—";
  }
  if (typeof value === "number" && Number.isFinite(value)) {
    return value.toLocaleString("zh-CN", { maximumFractionDigits: 2 });
  }
  if (typeof value === "boolean") {
    return value ? "是" : "否";
  }
  return String(value);
};

/**
 * 表格列展示顺序：维度在前、指标在后（阅读习惯），组内保持结果集原序。
 *
 * <p>只调整展示顺序，不改 `columns` 本身——图表定位、取值 key、导出顺序都以服务端下发的
 * 原始顺序为准。
 */
export const orderColumnsForDisplay = (columns: AskColumnMeta[]): AskColumnMeta[] => {
  const rank = (column: AskColumnMeta) =>
    column.role === "dimension" ? 0 : column.role === "metric" ? 1 : 2;
  return columns
    .map((column, index) => ({ column, index }))
    .sort((left, right) => rank(left.column) - rank(right.column) || left.index - right.index)
    .map((item) => item.column);
};

/** CSV 单元转义：含逗号/引号/换行时加引号，内部引号翻倍。 */
const escapeCsvCell = (value: string): string => {
  if (/[",\r\n]/.test(value)) {
    return `"${value.replaceAll('"', '""')}"`;
  }
  return value;
};

/**
 * 导出 CSV（W3 交互区）：列顺序用服务端下发的原始顺序，BOM 头保证 Excel 中文不乱码。
 * 行数据只来自结果集，不掺任何前端计算列。
 */
export const toCsv = (rows: Array<Record<string, unknown>>, columns: AskColumnMeta[]): string => {
  const header = columns.map((column) => escapeCsvCell(column.label || column.key)).join(",");
  const lines = rows.map((row) =>
    columns.map((column) => escapeCsvCell(formatCellValue(row[column.key]))).join(","),
  );
  return `\uFEFF${[header, ...lines].join("\r\n")}`;
};
