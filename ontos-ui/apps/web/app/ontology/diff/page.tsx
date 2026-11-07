import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Alert, Badge, Button, Input, PageHeader, SectionCard, Spinner } from "@ontos/ui";
import { useSearchParams } from "react-router-dom";

import { diffManifests, ontologyQueryKeys } from "@/service/ontology";
import { failureMessage } from "@/utils/error-message";

const SECTIONS: ReadonlyArray<{
  label: string;
  key: "metrics" | "dimensions" | "concepts" | "relations" | "rules" | "scenarios";
}> = [
  { label: "指标", key: "metrics" },
  { label: "分析维度", key: "dimensions" },
  { label: "业务对象", key: "concepts" },
  { label: "业务关联", key: "relations" },
  { label: "判定标准", key: "rules" },
  { label: "业务场景", key: "scenarios" },
];

export function DiffPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const manifestId = searchParams.get("manifestId") ?? "";
  const baseId = searchParams.get("baseId") ?? "";
  const [draftManifest, setDraftManifest] = useState(manifestId);
  const [draftBase, setDraftBase] = useState(baseId);

  const enabled = manifestId !== "" && baseId !== "" && manifestId !== baseId;
  const diffQuery = useQuery({
    queryKey: ontologyQueryKeys.diff(manifestId, baseId),
    queryFn: ({ signal }) => diffManifests(manifestId, baseId, signal),
    enabled,
  });

  return (
    <div className="px-6 py-5 max-md:px-4">
      <PageHeader title="版本对比" description="对比两个发布清单的资产差异；新增与移除逐类列出" />
      <SectionCard title="选择清单" className="mt-4">
        <div className="flex flex-wrap items-center gap-3 p-3">
          <Input
            className="w-72"
            placeholder="目标清单 ID"
            value={draftManifest}
            onChange={(event) => setDraftManifest(event.target.value)}
          />
          <Input
            className="w-72"
            placeholder="基准清单 ID"
            value={draftBase}
            onChange={(event) => setDraftBase(event.target.value)}
          />
          <Button
            size="small"
            disabled={draftManifest === "" || draftBase === "" || draftManifest === draftBase}
            onClick={() => setSearchParams({ manifestId: draftManifest, baseId: draftBase })}
          >
            对比
          </Button>
        </div>
      </SectionCard>
      {diffQuery.isFetching && <Spinner className="mt-4" />}
      {diffQuery.isError && <Alert className="mt-4">{failureMessage(diffQuery.error)}</Alert>}
      {diffQuery.data?.empty && (
        <Alert className="mt-4">{diffQuery.data.reason ?? "清单不存在"}</Alert>
      )}
      {diffQuery.data && !diffQuery.data.empty && (
        <div className="mt-4 grid gap-4 lg:grid-cols-2">
          {SECTIONS.map((section) => {
            const value = diffQuery.data?.[section.key];
            if (!value || (value.added.length === 0 && value.removed.length === 0)) return null;
            return (
              <SectionCard key={section.key} title={section.label}>
                <div className="p-3 space-y-2 text-sm">
                  {value.added.length > 0 && (
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone="success">新增</Badge>
                      {value.added.map((id) => (
                        <span key={id} className="text-xs px-2 py-1 rounded bg-[var(--accent)]">
                          {id}
                        </span>
                      ))}
                    </div>
                  )}
                  {value.removed.length > 0 && (
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone="danger">移除</Badge>
                      {value.removed.map((id) => (
                        <span
                          key={id}
                          className="text-xs px-2 py-1 rounded border border-[var(--border)] line-through"
                        >
                          {id}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              </SectionCard>
            );
          })}
        </div>
      )}
      {enabled &&
        diffQuery.data &&
        !diffQuery.data.empty &&
        Object.keys(diffQuery.data).filter((key) =>
          ["metrics", "dimensions", "concepts", "relations", "rules", "scenarios"].includes(key),
        ).length === 0 && <Alert className="mt-4">两个清单的资产完全一致</Alert>}
    </div>
  );
}
