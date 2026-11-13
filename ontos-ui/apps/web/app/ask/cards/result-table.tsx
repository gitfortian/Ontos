import { useMemo, useState } from "react";

import {
  flexRender,
  getCoreRowModel,
  getSortedRowModel,
  useReactTable,
  type Cell,
  type ColumnDef,
  type SortingState,
} from "@tanstack/react-table";
import { ChevronDown, ChevronUp } from "lucide-react";

import { formatCellValue, orderColumnsForDisplay } from "../result-summary";

/**
 * 明细区（TanStack Table）：表头点击排序；列定义来自服务端列元数据
 * （label 上屏、key 只取值），行数据不做任何加工。
 */
export function ResultTable({
  rows,
  columns,
}: {
  rows: Array<Record<string, unknown>>;
  columns: import("@/service/ask").AskColumnMeta[];
}) {
  const [sorting, setSorting] = useState<SortingState>([]);

  const columnDefs = useMemo<ColumnDef<Record<string, unknown>>[]>(
    () =>
      orderColumnsForDisplay(columns).map((column) => ({
        accessorKey: column.key,
        header: column.label,
        enableSorting: true,
        cell: (info) => formatCellValue(info.getValue()),
        meta: { numeric: column.numeric },
      })),
    [columns],
  );

  const table = useReactTable({
    data: rows,
    columns: columnDefs,
    state: { sorting },
    onSortingChange: setSorting,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
  });

  return (
    <div className="max-h-72 overflow-auto rounded-lg border border-border">
      <table className="w-full border-collapse text-xs">
        <thead className="sticky top-0 z-10 bg-muted">
          {table.getHeaderGroups().map((headerGroup) => (
            <tr key={headerGroup.id}>
              {headerGroup.headers.map((header) => {
                const numeric = Boolean(
                  (header.column.columnDef.meta as { numeric?: boolean } | undefined)?.numeric,
                );
                const sorted = header.column.getIsSorted();
                return (
                  <th
                    key={header.id}
                    className={`whitespace-nowrap border-b border-border px-2.5 py-1.5 font-normal text-muted-foreground ${
                      numeric ? "text-right" : "text-left"
                    }`}
                  >
                    <button
                      type="button"
                      className="inline-flex items-center gap-0.5 hover:text-foreground"
                      onClick={header.column.getToggleSortingHandler()}
                    >
                      {flexRender(header.column.columnDef.header, header.getContext())}
                      {sorted === "asc" ? (
                        <ChevronUp aria-hidden="true" size={11} />
                      ) : sorted === "desc" ? (
                        <ChevronDown aria-hidden="true" size={11} />
                      ) : null}
                    </button>
                  </th>
                );
              })}
            </tr>
          ))}
        </thead>
        <tbody>
          {table.getRowModel().rows.map((row) => (
            <tr key={row.id} className="border-b border-border last:border-b-0">
              {row.getVisibleCells().map((cell: Cell<Record<string, unknown>, unknown>) => {
                const numeric = Boolean(
                  (cell.column.columnDef.meta as { numeric?: boolean } | undefined)?.numeric,
                );
                return (
                  <td
                    key={cell.id}
                    className={`px-2.5 py-1.5 ${
                      numeric ? "text-right font-mono tabular-nums" : ""
                    }`}
                  >
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
