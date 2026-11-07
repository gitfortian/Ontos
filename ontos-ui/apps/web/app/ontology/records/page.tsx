import { useQuery } from "@tanstack/react-query";
import { Alert, Badge, Button, PageHeader, SectionCard, Spinner } from "@ontos/ui";

import { getPublishRecords, ontologyQueryKeys } from "@/service/ontology";
import { failureMessage } from "@/utils/error-message";

export function RecordsPage() {
  const recordsQuery = useQuery({
    queryKey: ontologyQueryKeys.publishRecords,
    queryFn: ({ signal }) => getPublishRecords(50, signal),
  });
  const records = recordsQuery.data;

  return (
    <div className="px-6 py-5 max-md:px-4">
      <PageHeader
        title="发布记录"
        description="每次预检与发布的留痕；清单发布本身走发布脚本，界面只读不改运行时"
        extra={
          <Button
            size="small"
            onClick={() => void recordsQuery.refetch()}
            loading={recordsQuery.isFetching}
          >
            刷新
          </Button>
        }
      />
      {recordsQuery.isError && <Alert className="mt-4">{failureMessage(recordsQuery.error)}</Alert>}
      {recordsQuery.isPending && <Spinner className="mt-6" />}
      {records && records.length === 0 && <Alert className="mt-4">还没有发布记录</Alert>}
      {records && records.length > 0 && (
        <SectionCard title={`共 ${records.length} 条`} className="mt-4">
          <div className="divide-y divide-[var(--border)]">
            {records.map((record, index) => (
              <div
                key={`${record.manifest_id}-${record.published_at}-${index}`}
                className="p-3 text-sm"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={record.dry_run ? "neutral" : "info"}>
                    {record.dry_run ? "预检" : "发布"}
                  </Badge>
                  <span className="font-medium">{record.manifest_id}</span>
                  <span className="text-xs text-[var(--muted-foreground)]">
                    {record.content_hash.slice(0, 12)}…
                  </span>
                </div>
                <div className="mt-1 text-xs text-[var(--muted-foreground)]">
                  {record.published_at} · 操作人 {record.published_by}
                  {record.source_ref ? ` · 来源 ${record.source_ref}` : ""}
                </div>
              </div>
            ))}
          </div>
        </SectionCard>
      )}
    </div>
  );
}
