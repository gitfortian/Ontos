import { useQuery } from "@tanstack/react-query";
import { Alert, Badge, Button, PageHeader, SectionCard, Spinner } from "@ontos/ui";

import { getCurrentManifest, manifestQueryKeys } from "@/service/ontology";
import { failureMessage } from "@/utils/error-message";

const VERSIONS: ReadonlyArray<{ label: string; key: keyof ManifestFieldValues }> = [
  { label: "本体版本", key: "ontologyVersion" },
  { label: "语义版本", key: "semanticVersion" },
  { label: "策略版本", key: "policyVersion" },
  { label: "规则版本", key: "ruleVersion" },
  { label: "时间解析版本", key: "timeParserVersion" },
  { label: "文案模板版本", key: "copyTemplateVersion" },
];

type ManifestFieldValues = {
  ontologyVersion: string;
  semanticVersion: string;
  policyVersion: string;
  ruleVersion: string;
  timeParserVersion: string;
  copyTemplateVersion: string;
};

export function ManifestsPage() {
  const query = useQuery({
    queryKey: manifestQueryKeys.current,
    queryFn: ({ signal }) => getCurrentManifest(signal),
  });
  const manifest = query.data;

  return (
    <div className="px-6 py-5 max-md:px-4">
      <PageHeader
        title="发布清单"
        description="同一 manifest_id 的内容永不更新；每次查询锚定内容哈希"
        extra={
          <Button size="small" onClick={() => void query.refetch()} loading={query.isFetching}>
            刷新
          </Button>
        }
      />

      {query.isError ? (
        <Alert className="mb-4">{failureMessage(query.error, "发布清单加载失败")}</Alert>
      ) : null}

      {query.isPending ? (
        <div className="flex items-center gap-2 py-10 text-sm text-[var(--muted-foreground)]">
          <Spinner /> 加载中
        </div>
      ) : null}

      {manifest ? (
        <div className="space-y-4">
          <SectionCard title="当前生效清单">
            <dl className="grid grid-cols-2 gap-4 lg:grid-cols-4">
              <div className="min-w-0">
                <dt className="text-xs text-[var(--muted-foreground)]">清单 ID</dt>
                <dd className="mt-1 truncate font-mono text-xs text-[var(--foreground)]">
                  {manifest.manifestId}
                </dd>
              </div>
              <div className="min-w-0">
                <dt className="text-xs text-[var(--muted-foreground)]">内容哈希</dt>
                <dd className="mt-1 truncate font-mono text-xs text-[var(--foreground)]">
                  {manifest.contentHash}
                </dd>
              </div>
              {VERSIONS.map((version) => (
                <div className="min-w-0" key={version.key}>
                  <dt className="text-xs text-[var(--muted-foreground)]">{version.label}</dt>
                  <dd className="mt-1 truncate font-mono text-xs text-[var(--foreground)]">
                    {manifest[version.key]}
                  </dd>
                </div>
              ))}
            </dl>
          </SectionCard>

          <SectionCard title="已发布资产">
            <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
              <div>
                <div className="mb-2 text-xs text-[var(--muted-foreground)]">
                  指标（{manifest.metricIds.length}）
                </div>
                <div className="flex flex-wrap gap-2">
                  {manifest.metricIds.map((id) => (
                    <Badge key={id} tone="info" className="font-mono">
                      {id}
                    </Badge>
                  ))}
                </div>
              </div>
              <div>
                <div className="mb-2 text-xs text-[var(--muted-foreground)]">
                  概念（{manifest.conceptIds.length}）
                </div>
                <div className="flex flex-wrap gap-2">
                  {manifest.conceptIds.map((id) => (
                    <Badge key={id} tone="success" className="font-mono">
                      {id}
                    </Badge>
                  ))}
                </div>
              </div>
            </div>
          </SectionCard>
        </div>
      ) : null}
    </div>
  );
}
