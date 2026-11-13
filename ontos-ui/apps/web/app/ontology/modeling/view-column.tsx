import { useQuery } from "@tanstack/react-query";

import { listDataViews } from "@/service/catalog";
import type { DataViewVO } from "@/service/catalog/types";

import { SelectField } from "../definitions/controls";

export interface ViewColumnFieldProps {
  label?: string;
  testId?: string;
  /** 当前选中的数据视图（B3 新口径）。 */
  viewId: string;
  /** 当前选中的裸列名。 */
  column: string;
  onChange: (viewId: string, column: string, dataType: string | null) => void;
}

/**
 * 视图资产列选择器（B3/T8）：视图 → 列两段选择，列清单来自视图快照
 * （资源侧扫描固化产物），选中列带物理类型供 valueType 自动映射。
 * 建模面从此不见物理表名——OpenBKN 双网络拆分的建模侧落点。
 */
export function ViewColumnField({
  label = "物理列",
  testId = "property-view-column",
  viewId,
  column,
  onChange,
}: ViewColumnFieldProps) {
  const viewsQuery = useQuery({
    queryKey: ["catalog", "data-views"],
    queryFn: ({ signal }) => listDataViews(signal),
  });
  const views: DataViewVO[] = (viewsQuery.data ?? []).filter((view) => view.status === "active");
  const current = views.find((view) => view.id === viewId) ?? null;

  return (
    <div className="space-y-3">
      <SelectField
        label="数据视图"
        testId={`${testId}-view`}
        value={viewId}
        options={views.map((view) => ({ value: view.id, label: `${view.name}（${view.id}）` }))}
        required
        onChange={(value) => onChange(value, "", null)}
      />
      {viewId && (
        <SelectField
          label={label}
          testId={testId}
          value={column}
          options={(current?.columns ?? []).map((item) => ({
            value: item.name,
            label: `${item.name}（${item.type}）`,
          }))}
          required
          onChange={(value) => {
            const meta = current?.columns.find((item) => item.name === value) ?? null;
            onChange(viewId, value, meta?.type ?? null);
          }}
        />
      )}
      {viewId && column && current && (
        <p className="pl-[104px] text-xs text-[var(--muted-foreground)]">
          当前绑定：{current.name} · {column}（快照 {current.contentHash.slice(0, 8)}）
        </p>
      )}
      {!viewsQuery.isPending && views.length === 0 && (
        <p className="pl-[104px] text-xs text-[var(--muted-foreground)]">
          还没有 active 数据视图——先在「数据源 → 数据视图」扫描固化。
        </p>
      )}
    </div>
  );
}
