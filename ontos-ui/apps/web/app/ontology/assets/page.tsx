import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Alert, Button, PageHeader, SectionCard, Spinner } from "@ontos/ui";

import { getAssets, getImpact, ontologyQueryKeys } from "@/service/ontology";
import { failureMessage } from "@/utils/error-message";

/** 业务化命名规格 §4：界面只出现业务叫法，不出现标准术语与内部 ID 语义解释。 */
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

export function AssetsPage() {
  const [selected, setSelected] = useState<string | null>(null);
  const assetsQuery = useQuery({
    queryKey: ontologyQueryKeys.assets(undefined),
    queryFn: ({ signal }) => getAssets(undefined, signal),
  });
  const assets = assetsQuery.data;
  const impactQuery = useQuery({
    queryKey: ontologyQueryKeys.impact(selected ?? ""),
    queryFn: ({ signal }) => getImpact(selected ?? "", signal),
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
            const ids = assets[section.key] ?? [];
            return (
              <SectionCard key={section.key} title={`${section.label}（${ids.length}）`}>
                <div className="flex flex-wrap gap-2 p-3">
                  {ids.map((id) => (
                    <button
                      key={id}
                      type="button"
                      onClick={() => setSelected(id)}
                      className={`text-xs px-2 py-1 rounded border cursor-pointer ${
                        selected === id
                          ? "border-[var(--primary)] bg-[var(--primary)] text-[var(--primary-foreground)]"
                          : "border-[var(--border)] hover:border-[var(--primary)]"
                      }`}
                    >
                      {id}
                    </button>
                  ))}
                </div>
              </SectionCard>
            );
          })}
          {selected && (
            <SectionCard title={`引用反查：${selected}`}>
              {impactQuery.isFetching && <Spinner className="m-3" />}
              {impactQuery.data && (
                <dl className="p-3 space-y-2 text-sm">
                  <div>
                    <dt className="text-[var(--muted-foreground)]">被判定标准引用</dt>
                    <dd>
                      {impactQuery.data.rules.length > 0 ? impactQuery.data.rules.join("、") : "无"}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-[var(--muted-foreground)]">被业务场景引用</dt>
                    <dd>
                      {impactQuery.data.scenarios.length > 0
                        ? impactQuery.data.scenarios.join("、")
                        : "无"}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-[var(--muted-foreground)]">允许该维度分组分析执行的指标</dt>
                    <dd>
                      {impactQuery.data.metricsAllowedDimensions.length > 0
                        ? impactQuery.data.metricsAllowedDimensions.join("、")
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
