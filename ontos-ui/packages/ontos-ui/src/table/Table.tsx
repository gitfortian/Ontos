import type { CSSProperties, Key } from "react";
import { cn } from "../cn";
import { Spinner } from "../spinner/Spinner";
import type { TableProps } from "./interface";

const rowKeyOf = <RecordType extends object>(
  record: RecordType,
  index: number,
  rowKey: TableProps<RecordType>["rowKey"],
): Key => {
  if (typeof rowKey === "function") return rowKey(record);
  if (rowKey && record[rowKey] != null) return String(record[rowKey]);
  return index;
};

/** Read-only data table: pages own queries, filters and server-side pagination. */
export function Table<RecordType extends object>({
  columns,
  dataSource,
  rowKey,
  loading = false,
  emptyText = "暂无数据",
  className,
  scroll,
}: TableProps<RecordType>) {
  const rows = dataSource ?? [];
  const minWidth = typeof scroll?.x === "number" ? scroll.x : undefined;

  return (
    <div
      className={cn(
        "overflow-x-auto rounded-[var(--radius)] border border-[var(--border)]",
        className,
      )}
    >
      <table className="w-full divide-y divide-[var(--border)] text-sm" style={{ minWidth }}>
        <thead className="bg-[var(--muted)]">
          <tr>
            {columns.map((column, index) => (
              <th
                key={String(column.key ?? index)}
                className="whitespace-nowrap px-4 py-2 text-left text-xs font-medium text-[var(--muted-foreground)]"
                style={{
                  width: column.width as CSSProperties["width"],
                  minWidth: column.minWidth as CSSProperties["minWidth"],
                  textAlign: column.align ?? "left",
                }}
              >
                {column.title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-[var(--border)] bg-[var(--card)]">
          {loading ? (
            <tr>
              <td
                colSpan={columns.length}
                className="px-4 py-8 text-center text-xs text-[var(--muted-foreground)]"
              >
                <span className="inline-flex items-center gap-2">
                  <Spinner size="small" /> 加载中
                </span>
              </td>
            </tr>
          ) : rows.length === 0 ? (
            <tr>
              <td
                colSpan={columns.length}
                className="px-4 py-8 text-center text-xs text-[var(--muted-foreground)]"
              >
                {emptyText}
              </td>
            </tr>
          ) : (
            rows.map((record, rowIndex) => (
              <tr key={rowKeyOf(record, rowIndex, rowKey)} className="hover:bg-[var(--accent)]">
                {columns.map((column, columnIndex) => {
                  const value = column.dataIndex ? record[column.dataIndex] : undefined;
                  return (
                    <td
                      key={String(column.key ?? columnIndex)}
                      className="whitespace-nowrap px-4 py-2 text-[var(--foreground)]"
                      style={{ textAlign: column.align ?? "left" }}
                    >
                      {column.render
                        ? column.render(value, record, rowIndex)
                        : value === null || value === undefined
                          ? "-"
                          : String(value)}
                    </td>
                  );
                })}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
