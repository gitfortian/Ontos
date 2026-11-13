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

export interface ResultBar {
  label: string;
  value: number;
}

/**
 * 图表数据：只接受「一个维度 + 一个指标」，超过上限截断并如实返回 truncated。
 * 维度值可能是日期、编码或文本——不做类型猜测，统一按分类轴呈现。
 */
export const buildBars = (
  rows: Array<Record<string, unknown>>,
  columns: AskColumnMeta[],
  limit = 12,
): { bars: ResultBar[]; truncated: boolean } => {
  const dimension = columns.find((column) => column.role === "dimension");
  const metric = columns.find((column) => column.role === "metric" && column.numeric);
  if (!dimension || !metric) {
    return { bars: [], truncated: false };
  }
  const bars: ResultBar[] = [];
  for (const row of rows) {
    const value = row[metric.key];
    if (typeof value !== "number" || !Number.isFinite(value)) {
      continue;
    }
    const rawLabel = row[dimension.key];
    bars.push({ label: rawLabel == null ? "—" : String(rawLabel), value });
  }
  if (bars.length <= limit) {
    return { bars, truncated: false };
  }
  return { bars: bars.slice(0, limit), truncated: true };
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
