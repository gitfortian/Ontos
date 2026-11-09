/**
 * 结果单元格展示格式化（R-25）。
 *
 * 服务端时间列由 PG 按 date_trunc（会话时区 Asia/Shanghai）产出、Jackson 序列化为 UTC ISO，
 * 原样展示会与「上个月」「上季度」等用户口径差 8 小时；这里统一换回上海墙钟，
 * 与编译期绑定的 wall-clock 口径一致。其余值不做猜测转换。
 */

const ISO_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?$/;

/** Asia/Shanghai 墙钟：2026-03-01T00:00:00Z → "2026-03-01 08:00:00"。 */
export const formatShanghaiTime = (value: string): string => {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).formatToParts(date);
  const get = (type: string) => parts.find((part) => part.type === type)?.value ?? "";
  return `${get("year")}-${get("month")}-${get("day")} ${get("hour")}:${get("minute")}:${get("second")}`;
};

export const formatCellValue = (value: unknown): string => {
  if (value === null || value === undefined) return "-";
  if (typeof value === "string" && ISO_TIMESTAMP.test(value)) return formatShanghaiTime(value);
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
};
