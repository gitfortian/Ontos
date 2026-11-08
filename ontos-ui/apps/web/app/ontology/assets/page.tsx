import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Alert, Button, PageHeader, SectionCard, Spinner } from "@ontos/ui";

import { getAssets, getImpact, ontologyQueryKeys } from "@/service/ontology";
import type { AssetChipVO, AssetsVO } from "@/service/ontology/types";
import { failureMessage } from "@/utils/error-message";

/** 业务化命名规格 §4：芯片展示业务名（name），内部 ID 不作为可见文本。 */

const SECTIONS: ReadonlyArray<{
  label: string;
  key: "metrics" | "dimensions" | "concepts" | "properties" | "relations" | "rules" | "scenarios";
}> = [
  { label: "指标", key: "metrics" },
  { label: "分析维度", key: "dimensions" },
  { label: "业务对象", key: "concepts" },
  { label: "信息项", key: "properties" },
  { label: "业务关联", key: "relations" },
  { label: "判定标准", key: "rules" },
  { label: "业务场景", key: "scenarios" },
];

type SectionKey = (typeof SECTIONS)[number]["key"];

function chipsOf(assets: AssetsVO | undefined, key: SectionKey): AssetChipVO[] {
  const value = assets?.[key];
  return Array.isArray(value) ? value : [];
}

/** 引用反查结果 id → 业务名（用已加载的词表解析；解析不到才退回显示 ID）。 */
function toNames(assets: AssetsVO | undefined, ids: readonly string[]): string[] {
  const nameById = new Map<string, string>();
  for (const key of [
    "metrics",
    "dimensions",
    "concepts",
    "properties",
    "relations",
    "rules",
    "scenarios",
  ] as const) {
    for (const chip of chipsOf(assets, key)) {
      nameById.set(chip.id, chip.name);
    }
  }
  return ids.map((id) => nameById.get(id) ?? id);
}

export function AssetsPage() {
  const [selected, setSelected] = useState<AssetChipVO | null>(null);
  const assetsQuery = useQuery({
    queryKey: ontologyQueryKeys.assets(undefined),
    queryFn: ({ signal }) => getAssets(undefined, signal),
  });
  const assets = assetsQuery.data;
  const impactQuery = useQuery({
    queryKey: ontologyQueryKeys.impact(selected?.id ?? ""),
    queryFn: ({ signal }) => getImpact(selected?.id ?? "", signal),
    enabled: selected !== null,
  });

  return (
    <div className="px-6 py-5 max-md:px-4">
      <PageHeader
        title="数据词典"
        description="当前生效清单里的全部资产；点选任意一项查看它被哪些判定标准和场景引用"
        extra={
          <Button
            size="small"
            onClick={() => void assetsQuery.refetch()}
            loading={assetsQuery.isFetching}
          >
            刷新
          </Button>
        }
      />
      {assetsQuery.isError && <Alert className="mt-4">{failureMessage(assetsQuery.error)}</Alert>}
      {assetsQuery.isPending && <Spinner className="mt-6" />}
      {assets?.empty && <Alert className="mt-4">当前没有已发布的清单</Alert>}
      {assets && !assets.empty && (
        <div className="mt-4 grid gap-4 lg:grid-cols-2">
          {SECTIONS.map((section) => {
            const chips = chipsOf(assets, section.key);
            return (
              <SectionCard key={section.key} title={`${section.label}（${chips.length}）`}>
                <div className="flex flex-wrap gap-2 p-3">
                  {chips.map((chip) => (
                    <button
                      key={chip.id}
                      type="button"
                      title={chip.id}
                      onClick={() => setSelected(chip)}
                      className={`text-sm px-2.5 py-1 rounded border cursor-pointer ${
                        selected?.id === chip.id
                          ? "border-[var(--primary)] bg-[var(--primary)] text-[var(--primary-foreground)]"
                          : "border-[var(--border)] hover:border-[var(--primary)]"
                      }`}
                    >
                      {chip.name}
                    </button>
                  ))}
                </div>
              </SectionCard>
            );
          })}
          {selected && (
            <SectionCard title={`引用反查：${selected.name}`}>
              {impactQuery.isFetching && <Spinner className="m-3" />}
              {impactQuery.data && (
                <dl className="p-3 space-y-2 text-sm">
                  <div>
                    <dt className="text-[var(--muted-foreground)]">被判定标准引用</dt>
                    <dd>
                      {toNames(assets, impactQuery.data.rules).length > 0
                        ? toNames(assets, impactQuery.data.rules).join("、")
                        : "无"}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-[var(--muted-foreground)]">被业务场景引用</dt>
                    <dd>
                      {toNames(assets, impactQuery.data.scenarios).length > 0
                        ? toNames(assets, impactQuery.data.scenarios).join("、")
                        : "无"}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-[var(--muted-foreground)]">允许该维度分组分析执行的指标</dt>
                    <dd>
                      {toNames(assets, impactQuery.data.metricsAllowedDimensions).length > 0
                        ? toNames(assets, impactQuery.data.metricsAllowedDimensions).join("、")
                        : "无"}
                    </dd>
                  </div>
                </dl>
              )}
            </SectionCard>
          )}
        </div>
      )}
    </div>
  );
}
