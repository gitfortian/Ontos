import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, PageHeader, SectionCard, Spinner, toast } from "@ontos/ui";

import {
  createSemanticAssetDraft,
  getSemanticAsset,
  getSemanticAssetOptions,
  listSemanticAssets,
  reviseSemanticAsset,
  saveSemanticAssetDraft,
  semanticAssetQueryKeys,
  submitSemanticAssetReview,
  validateSemanticAsset,
  withdrawSemanticAssetReview,
} from "@/service/ontology";
import type {
  SemanticAssetOptionsVO,
  SemanticAssetStatus,
  SemanticAssetType,
  SemanticValidationVO,
} from "@/service/ontology/types";
import { failureMessage } from "@/utils/error-message";

import { DimensionEditor } from "./dimension-editor";
import { MetricEditor } from "./metric-editor";
import { EMPTY_DIMENSION, EMPTY_METRIC, dimensionFormValues, metricFormValues } from "./schema";

const TYPE_OPTIONS: ReadonlyArray<{ value: SemanticAssetType; label: string }> = [
  { value: "metric", label: "指标" },
  { value: "dimension", label: "维度" },
];

const STATUS_LABEL: Record<SemanticAssetStatus, string> = {
  draft: "草稿",
  reviewing: "审核中",
  published: "已发布",
  retired: "已退役",
};

const EMPTY_OPTIONS: SemanticAssetOptionsVO = {
  concepts: [],
  properties: [],
  dimensions: [],
  metrics: [],
  relations: [],
};

export function DefinitionsPage() {
  const queryClient = useQueryClient();
  const [type, setType] = useState<SemanticAssetType>("metric");
  const [statusFilter, setStatusFilter] = useState("");
  const [keyword, setKeyword] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [validation, setValidation] = useState<SemanticValidationVO | null>(null);

  const optionsQuery = useQuery({
    queryKey: semanticAssetQueryKeys.options,
    queryFn: ({ signal }) => getSemanticAssetOptions(signal),
  });
  const listQuery = useQuery({
    queryKey: semanticAssetQueryKeys.list(type, statusFilter, keyword, 1, 50),
    queryFn: ({ signal }) =>
      listSemanticAssets(type, { status: statusFilter, keyword, page: 1, pageSize: 50 }, signal),
  });
  const detailQuery = useQuery({
    queryKey: semanticAssetQueryKeys.detail(type, selectedId ?? ""),
    queryFn: ({ signal }) => getSemanticAsset(type, selectedId ?? "", signal),
    enabled: selectedId !== null && !creating,
  });

  const invalidate = async () => {
    await queryClient.invalidateQueries({ queryKey: ["catalog", "asset"] });
  };

  const saveMutation = useMutation({
    mutationFn: async (definition: Record<string, unknown>) => {
      if (creating) {
        return createSemanticAssetDraft(type, definition);
      }
      if (!selectedId || !detailQuery.data) {
        throw new Error("未选择资产");
      }
      return saveSemanticAssetDraft(type, selectedId, definition, detailQuery.data.revision);
    },
    onSuccess: async (saved) => {
      setCreating(false);
      setSelectedId(saved.id);
      setValidation(null);
      await invalidate();
      toast.success("草稿已保存");
    },
    onError: (error) => toast.error(failureMessage(error)),
  });

  const validateMutation = useMutation({
    mutationFn: async (definition: Record<string, unknown>) => {
      const id = selectedId ?? String(definition.id ?? "");
      return validateSemanticAsset(type, id, definition);
    },
    onSuccess: (result) => setValidation(result),
    onError: (error) => toast.error(failureMessage(error)),
  });

  const submitMutation = useMutation({
    mutationFn: async (definition: Record<string, unknown>) => {
      let current;
      if (creating) {
        current = await createSemanticAssetDraft(type, definition);
      } else {
        if (!selectedId || !detailQuery.data) {
          throw new Error("未选择资产");
        }
        current = await saveSemanticAssetDraft(
          type,
          selectedId,
          definition,
          detailQuery.data.revision,
        );
      }
      return submitSemanticAssetReview(type, current.id, current.revision);
    },
    onSuccess: async (saved) => {
      setCreating(false);
      setSelectedId(saved.id);
      setValidation(null);
      await invalidate();
      toast.success("已提交审核");
    },
    onError: (error) => toast.error(failureMessage(error)),
  });

  const withdrawMutation = useMutation({
    mutationFn: async () => {
      if (!selectedId || !detailQuery.data) {
        throw new Error("未选择资产");
      }
      return withdrawSemanticAssetReview(type, selectedId, detailQuery.data.revision);
    },
    onSuccess: async () => {
      await invalidate();
      toast.success("已撤回为草稿");
    },
    onError: (error) => toast.error(failureMessage(error)),
  });

  const reviseMutation = useMutation({
    mutationFn: (id: string) => reviseSemanticAsset(type, id),
    onSuccess: async (revised) => {
      setSelectedId(revised.id);
      setCreating(false);
      setValidation(null);
      await invalidate();
      toast.success("已回迁为草稿，可编辑后重新提交");
    },
    onError: (error) => toast.error(failureMessage(error)),
  });

  const options = optionsQuery.data ?? EMPTY_OPTIONS;
  const published = type === "metric" ? options.metrics : options.dimensions;
  const list = listQuery.data?.list ?? [];
  const detail = detailQuery.data;
  const busy =
    saveMutation.isPending ||
    submitMutation.isPending ||
    validateMutation.isPending ||
    reviseMutation.isPending;

  const switchType = (next: SemanticAssetType) => {
    setType(next);
    setSelectedId(null);
    setCreating(false);
    setValidation(null);
  };

  return (
    <div className="px-6 py-5 max-md:px-4">
      <PageHeader
        title="语义定义"
        description="以「业务对象 + 属性」定义指标与维度；草稿校验复用发布侧同一套结构规则"
        extra={
          <Button
            variant="primary"
            size="small"
            onClick={() => {
              setCreating(true);
              setSelectedId(null);
              setValidation(null);
            }}
          >
            新建{type === "metric" ? "指标" : "维度"}
          </Button>
        }
      />

      <div className="mt-4 flex gap-2">
        {TYPE_OPTIONS.map((option) => (
          <Button
            key={option.value}
            size="small"
            variant={type === option.value ? "primary" : "secondary"}
            onClick={() => switchType(option.value)}
          >
            {option.label}
          </Button>
        ))}
      </div>

      {optionsQuery.isError && <Alert className="mt-4">{failureMessage(optionsQuery.error)}</Alert>}
      {optionsQuery.isPending && <Spinner className="mt-6" />}

      {optionsQuery.isSuccess && (
        <div className="mt-4 grid gap-4 lg:grid-cols-[320px_minmax(0,1fr)]">
          <div className="space-y-4">
            <SectionCard title={`草稿（${listQuery.data?.total ?? 0}）`}>
              <div className="flex flex-col gap-2 p-3">
                <div className="flex gap-2">
                  <select
                    className="h-8 min-w-0 flex-1 rounded border border-[var(--border)] bg-[var(--background)] px-2 text-xs"
                    value={statusFilter}
                    aria-label="状态筛选"
                    onChange={(event) => setStatusFilter(event.target.value)}
                  >
                    <option value="">全部状态</option>
                    <option value="draft">草稿</option>
                    <option value="reviewing">审核中</option>
                  </select>
                  <input
                    className="h-8 min-w-0 flex-1 rounded border border-[var(--border)] bg-[var(--background)] px-2 text-xs"
                    placeholder="搜索 ID / 名称"
                    value={keyword}
                    onChange={(event) => setKeyword(event.target.value)}
                  />
                </div>
                {listQuery.isError && <Alert>{failureMessage(listQuery.error)}</Alert>}
                {listQuery.isPending && <Spinner />}
                {listQuery.isSuccess && list.length === 0 && (
                  <div className="py-6 text-center text-xs text-[var(--muted-foreground)]">
                    没有符合条件的草稿
                  </div>
                )}
                <ul className="space-y-1">
                  {list.map((item) => (
                    <li key={item.id}>
                      <button
                        type="button"
                        onClick={() => {
                          setSelectedId(item.id);
                          setCreating(false);
                          setValidation(null);
                        }}
                        className={`w-full rounded border px-2 py-1.5 text-left ${
                          selectedId === item.id
                            ? "border-[var(--primary)] bg-[var(--accent)]"
                            : "border-transparent hover:border-[var(--border)]"
                        }`}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <span className="truncate text-sm">{item.name}</span>
                          <span className="shrink-0 text-xs text-[var(--muted-foreground)]">
                            {STATUS_LABEL[item.status]}
                          </span>
                        </div>
                        <div className="mt-0.5 truncate font-mono text-xs text-[var(--muted-foreground)]">
                          {item.id}
                        </div>
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            </SectionCard>

            <SectionCard title={`生效资产（${published.length}）`}>
              <div className="p-3 pt-1">
                <p className="mb-2 text-xs text-[var(--muted-foreground)]">
                  修订会把生效清单中的定义回迁为草稿（内容以清单为准），编辑器随后按清单内容预填。
                </p>
                <ul className="space-y-1">
                  {published.map((item) => (
                    <li
                      key={item.id}
                      className="flex items-center justify-between gap-2 rounded px-2 py-1.5 hover:bg-[var(--accent)]"
                    >
                      <div className="min-w-0">
                        <div className="truncate text-sm">{item.name}</div>
                        <div className="truncate font-mono text-xs text-[var(--muted-foreground)]">
                          {item.id}
                        </div>
                      </div>
                      <Button
                        size="small"
                        disabled={reviseMutation.isPending}
                        onClick={() => reviseMutation.mutate(item.id)}
                      >
                        修订
                      </Button>
                    </li>
                  ))}
                  {published.length === 0 && (
                    <li className="py-4 text-center text-xs text-[var(--muted-foreground)]">
                      当前类型还没有生效资产
                    </li>
                  )}
                </ul>
              </div>
            </SectionCard>
          </div>

          <SectionCard
            title={
              creating
                ? `新建${type === "metric" ? "指标" : "维度"}`
                : detail
                  ? `${detail.name}（${STATUS_LABEL[detail.status]}）`
                  : "编辑"
            }
          >
            <div className="p-3">
              {detail?.status === "reviewing" && (
                <div className="mb-3 flex justify-end">
                  <Button
                    size="small"
                    loading={withdrawMutation.isPending}
                    onClick={() => withdrawMutation.mutate()}
                  >
                    撤回审核
                  </Button>
                </div>
              )}
              {!creating && !selectedId && (
                <div className="py-10 text-center text-sm text-[var(--muted-foreground)]">
                  从左侧选择资产，或新建草稿
                </div>
              )}
              {!creating && selectedId && detailQuery.isPending && <Spinner />}
              {!creating && selectedId && detailQuery.isError && (
                <Alert>{failureMessage(detailQuery.error)}</Alert>
              )}

              {(creating || detail) &&
                (type === "metric" ? (
                  <MetricEditor
                    key={creating ? "new-metric" : (detail?.id ?? "metric")}
                    initial={creating ? EMPTY_METRIC : metricFormValues(detail?.definition ?? {})}
                    idLocked={!creating}
                    options={options}
                    busy={busy}
                    onSave={async (definition) => {
                      await saveMutation.mutateAsync(definition);
                    }}
                    onValidate={(definition) => validateMutation.mutateAsync(definition)}
                    onSubmitReview={async (definition) => {
                      await submitMutation.mutateAsync(definition);
                    }}
                  />
                ) : (
                  <DimensionEditor
                    key={creating ? "new-dimension" : (detail?.id ?? "dimension")}
                    initial={
                      creating ? EMPTY_DIMENSION : dimensionFormValues(detail?.definition ?? {})
                    }
                    idLocked={!creating}
                    options={options}
                    busy={busy}
                    onSave={async (definition) => {
                      await saveMutation.mutateAsync(definition);
                    }}
                    onValidate={(definition) => validateMutation.mutateAsync(definition)}
                    onSubmitReview={async (definition) => {
                      await submitMutation.mutateAsync(definition);
                    }}
                  />
                ))}

              {validation && (
                <Alert className="mt-4">
                  {validation.valid ? (
                    "校验通过"
                  ) : (
                    <div>
                      <div>校验未通过（{validation.violations.length} 项）</div>
                      <ul className="mt-1 list-disc pl-4 text-xs">
                        {validation.violations.map((violation) => (
                          <li key={`${violation.location}-${violation.message}`}>
                            {violation.location}：{violation.message}
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}
                </Alert>
              )}
            </div>
          </SectionCard>
        </div>
      )}
    </div>
  );
}
