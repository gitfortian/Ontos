import { useState } from "react";
import { useQuery } from "@tanstack/react-query";

import {
  browseDataSourceCatalog,
  dataSourceQueryKeys,
  getDataSourceColumns,
} from "@/service/catalog";

import { SelectField } from "../definitions/controls";

export interface CatalogColumnFieldProps {
  label: string;
  testId: string;
  /** 当前值：「表.列」或空串。 */
  value: string;
  /** 选中列回调；dataType 为该列在真实目录中的类型，清空选择时为 null。 */
  onChange: (column: string, dataType: string | null) => void;
}

/**
 * 真实目录列选择器（B2 目录 + B1/P2-B 表单）：目录 Schema → 物理表 → 物理列 三段选择，
 * 产出清单口径的「表.列」。schema 段不进值——清单按 表.列 引用物理列，schema 由连接配置决定。
 */
export function CatalogColumnField({ label, testId, value, onChange }: CatalogColumnFieldProps) {
  const [schema, setSchema] = useState("public");
  const [table, setTable] = useState(value.includes(".") ? value.split(".")[0]! : "");

  const browseQuery = useQuery({
    queryKey: dataSourceQueryKeys.browse(schema),
    queryFn: ({ signal }) => browseDataSourceCatalog(schema, signal),
  });
  const columnsQuery = useQuery({
    queryKey: dataSourceQueryKeys.columns(schema, table),
    queryFn: ({ signal }) => getDataSourceColumns(schema, table, signal),
    enabled: table !== "",
  });

  const columnPart =
    table !== "" && value.startsWith(`${table}.`) ? value.slice(table.length + 1) : "";

  const clearColumn = () => {
    if (columnPart) {
      onChange("", null);
    }
  };

  return (
    <div className="space-y-2 rounded-[var(--radius)] border border-[var(--border)] p-2.5">
      <div className="grid grid-cols-2 gap-2">
        <SelectField
          label="目录 Schema"
          testId={`${testId}-schema`}
          value={schema}
          options={(browseQuery.data?.schemas ?? []).map((item) => ({
            value: item,
            label: item,
          }))}
          onChange={(next) => {
            setSchema(next);
            setTable("");
            clearColumn();
          }}
        />
        <SelectField
          label="物理表"
          testId={`${testId}-table`}
          value={table}
          options={(browseQuery.data?.tables ?? []).map((item) => ({
            value: item.name,
            label: item.name,
          }))}
          onChange={(next) => {
            setTable(next);
            clearColumn();
          }}
        />
      </div>
      <SelectField
        label={label}
        testId={testId}
        value={columnPart}
        options={(columnsQuery.data ?? []).map((item) => ({
          value: item.name,
          label: `${item.name}（${item.dataType}）`,
        }))}
        required
        onChange={(next) => {
          const meta = (columnsQuery.data ?? []).find((item) => item.name === next);
          onChange(table ? `${table}.${next}` : next, meta?.dataType ?? null);
        }}
      />
    </div>
  );
}
