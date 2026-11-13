import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Badge, Button, Input, PageHeader, SectionCard, Spinner } from "@ontos/ui";

import {
  createSemanticAssetDraft,
  getSemanticAsset,
  getSemanticAssetOptions,
  listSemanticAssets,
  reviseSemanticAsset,
  saveSemanticAssetDraft,
  semanticAssetQueryKeys,
  submitSemanticAssetReview,
  synthesizeSemanticAssets,
  validateSemanticAsset,
  withdrawSemanticAssetReview,
} from "@/service/ontology";
import type {
  SemanticAssetOptionsVO,
  SemanticAssetSummaryVO,
  SemanticAssetType,
  SemanticAssetVO,
  SemanticValidationVO,
  SemanticSynthesisVO,
} from "@/service/ontology/types";
import { failureMessage } from "@/utils/error-message";

import { ConceptEditor } from "./concept-editor";
import { PropertyEditor } from "./property-editor";
import { RelationEditor } from "./relation-editor";
import {
  conceptFormOf,
  propertyFormOf,
  relationFormOf,
  valueTypeOfColumn,
  type ConceptFormValues,
  type PropertyFormValues,
  type RelationFormValues,
} from "./schema";

type TabKey = "concepts" | "properties" | "relations";

const TABS: ReadonlyArray<{ key: TabKey; label: string }> = [
  { key: "concepts", label: "业务对象" },
  { key: "properties", label: "信息项" },
  { key: "relations", label: "业务关联" },
];

const VALUE_TYPE_LABEL: Record<string, string> = {
  string: "文本",
  integer: "整数",
  decimal: "小数",
  boolean: "布尔",
  date: "日期",
  timestamp: "时间戳",
};

const ASSET_TYPE: Record<TabKey, SemanticAssetType> = {
  concepts: "concept",
  properties: "property",
  relations: "relation",
};

const COLUMNS: Record<TabKey, string[]> = {
  concepts: ["业务对象", "领域", "信息项"],
  properties: ["信息项", "所属对象", "值类型", "过滤"],
  relations: ["业务关联", "关系两端"],
};

const STATUS_LABEL: Record<string, string> = {
  draft: "草稿",
  reviewing: "审核中",
  published: "已发布",
  retired: "退役",
};

interface Row {
  id: string;
  name: string;
  summary: string;
  cells: string[];
  searchText: string;
}

interface EditorState {
  mode: "create" | "edit";
  draft?: SemanticAssetVO;
  /** 从数据源目录跳转预填（B1 P2-C 前半）：仅 create 模式消费。 */
  prefilledColumn?: string;
  prefilledDataType?: string;
}

function rowsOf(tab: TabKey, options: SemanticAssetOptionsVO | undefined): Row[] {
  if (!options) return [];
  const conceptName = new Map(options.concepts.map((concept) => [concept.id, concept.name]));
  if (tab === "concepts") {
    return options.concepts.map((concept) => ({
      id: concept.id,
      name: concept.name,
      summary: concept.summary ?? "",
      cells: [concept.domain ?? "—", `${concept.propertyCount ?? 0} 个信息项`],
      searchText: `${concept.name} ${concept.id} ${concept.domain ?? ""}`,
    }));
  }
  if (tab === "properties") {
    return options.properties.map((property) => ({
      id: property.id,
      name: property.name,
      summary: property.summary ?? "",
      cells: [
        conceptName.get(property.conceptId) ?? property.conceptId,
        VALUE_TYPE_LABEL[property.valueType] ?? property.valueType,
        property.filterable ? "可过滤" : "不可过滤",
      ],
      searchText: `${property.name} ${property.id} ${property.conceptId}`,
    }));
  }
  return options.relations.map((relation) => ({
    id: relation.id,
    name: relation.name,
    summary: relation.summary ?? "",
    cells: [
      `${conceptName.get(relation.from) ?? relation.from ?? "—"} → ${
        conceptName.get(relation.to) ?? relation.to ?? "—"
      }`,
    ],
    searchText: `${relation.name} ${relation.id} ${relation.from} ${relation.to}`,
  }));
}

/**
 * 本体建模（B2/P2-B）：对象 / 信息项 / 业务关联的只读浏览 + 草稿编辑。
 * 草稿走 `/catalog/assets/{assetType}`（B1 P0），校验复用发布侧同一套规则；
 * 信息项的 column 从数据源插件的真实目录选择（B2 Phase 1）。
 */
export function ModelingPage() {
  const [tab, setTab] = useState<TabKey>("concepts");
  const [keyword, setKeyword] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [editor, setEditor] = useState<EditorState | null>(null);
  const [editorError, setEditorError] = useState<string | null>(null);
  const [validation, setValidation] = useState<SemanticValidationVO | null>(null);
  const [busy, setBusy] = useState(false);
  const [synthesis, setSynthesis] = useState<SemanticSynthesisVO | null>(null);
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();

  const optionsQuery = useQuery({
    queryKey: semanticAssetQueryKeys.options,
    queryFn: ({ signal }) => getSemanticAssetOptions(signal),
  });
  const options = optionsQuery.data;
  const assetType = ASSET_TYPE[tab];

  // 目录扫描建资产（B1 P2-C 前半）：数据源页「建信息项」跳转预填；仅挂载时消费一次并清参
  useEffect(() => {
    const pendingColumn = searchParams.get("column");
    if (!pendingColumn) return;
    const pendingTab = searchParams.get("tab");
    setTab(
      pendingTab === "properties" || pendingTab === "concepts" || pendingTab === "relations"
        ? (pendingTab as TabKey)
        : "properties",
    );
    setSelectedId(null);
    setValidation(null);
    setEditor({
      mode: "create",
      prefilledColumn: pendingColumn,
      prefilledDataType: searchParams.get("dataType") ?? undefined,
    });
    setSearchParams({}, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 仅挂载消费一次
  }, []);
  const draftsQuery = useQuery({
    queryKey: semanticAssetQueryKeys.list(assetType, "", "", 1, 50),
    queryFn: ({ signal }) => listSemanticAssets(assetType, { page: 1, pageSize: 50 }, signal),
  });
  const drafts = draftsQuery.data?.list ?? [];

  const counts: Record<TabKey, number> = {
    concepts: options?.concepts.length ?? 0,
    properties: options?.properties.length ?? 0,
    relations: options?.relations.length ?? 0,
  };
  const rows = (() => {
    const all = rowsOf(tab, options);
    const query = keyword.trim().toLowerCase();
    return query ? all.filter((row) => row.searchText.toLowerCase().includes(query)) : all;
  })();
  const selected = rows.find((row) => row.id === selectedId) ?? null;

  const invalidateDrafts = () => {
    void queryClient.invalidateQueries({
      queryKey: semanticAssetQueryKeys.list(assetType, "", "", 1, 50),
    });
  };

  const openCreate = () => {
    setEditorError(null);
    setValidation(null);
    setEditor({ mode: "create" });
  };

  const openEdit = async (draft: SemanticAssetSummaryVO) => {
    setEditorError(null);
    setValidation(null);
    setBusy(true);
    try {
      const detail = await getSemanticAsset(assetType, draft.id);
      setEditor({ mode: "edit", draft: detail });
    } catch (error) {
      setEditorError(failureMessage(error));
    } finally {
      setBusy(false);
    }
  };

  const closeEditor = () => {
    setEditor(null);
    setEditorError(null);
    setValidation(null);
  };

  const handleValidate = async (definition: Record<string, unknown>) => {
    setEditorError(null);
    const result = await validateSemanticAsset(assetType, String(definition.id ?? ""), definition);
    setValidation(result);
    return result;
  };

  const handleSave = async (definition: Record<string, unknown>, submitAfter: boolean) => {
    setEditorError(null);
    setBusy(true);
    try {
      if (editor?.mode === "create") {
        const created = await createSemanticAssetDraft(assetType, definition);
        if (submitAfter) {
          await submitSemanticAssetReview(assetType, created.id, created.revision);
        }
      } else if (editor?.draft) {
        const saved = await saveSemanticAssetDraft(
          assetType,
          editor.draft.id,
          definition,
          editor.draft.revision,
        );
        if (submitAfter) {
          await submitSemanticAssetReview(assetType, editor.draft.id, saved.revision);
        }
      }
      invalidateDrafts();
      closeEditor();
    } catch (error) {
      setEditorError(failureMessage(error));
    } finally {
      setBusy(false);
    }
  };

  const handleWithdraw = async (draft: SemanticAssetSummaryVO) => {
    setEditorError(null);
    setBusy(true);
    try {
      await withdrawSemanticAssetReview(assetType, draft.id, draft.revision);
      invalidateDrafts();
    } catch (error) {
      setEditorError(failureMessage(error));
    } finally {
      setBusy(false);
    }
  };

  const handleRevise = async () => {
    if (!selected) return;
    setEditorError(null);
    setBusy(true);
    try {
      const revised = await reviseSemanticAsset(assetType, selected.id);
      invalidateDrafts();
      setValidation(null);
      setEditor({ mode: "edit", draft: revised });
    } catch (error) {
      setEditorError(failureMessage(error));
    } finally {
      setBusy(false);
    }
  };

  const handleSynthesize = async () => {
    setEditorError(null);
    setBusy(true);
    try {
      const report = await synthesizeSemanticAssets();
      setSynthesis(report);
      invalidateDrafts();
      void queryClient.invalidateQueries({ queryKey: semanticAssetQueryKeys.options });
    } catch (error) {
      setEditorError(failureMessage(error));
    } finally {
      setBusy(false);
    }
  };

  const editorInitial = (): ConceptFormValues | PropertyFormValues | RelationFormValues => {
    if (editor?.mode === "edit") {
      const definition = editor.draft?.definition ?? {};
      if (tab === "concepts") return conceptFormOf(definition);
      if (tab === "properties") return propertyFormOf(definition);
      return relationFormOf(definition);
    }
    if (tab === "concepts") {
      return { id: "", name: "", domain: "", aliases: "", summary: "", identityProperty: "" };
    }
    if (tab === "properties") {
      const mapped =
        editor?.mode === "create" && editor.prefilledDataType
          ? valueTypeOfColumn(editor.prefilledDataType)
          : null;
      return {
        id: "",
        name: "",
        conceptId: "",
        column: editor?.mode === "create" ? (editor.prefilledColumn ?? "") : "",
        valueType: mapped ?? "string",
        aliases: "",
        summary: "",
        filterable: false,
      };
    }
    return {
      id: "",
      name: "",
      aliases: "",
      summary: "",
      from: "",
      to: "",
      fromColumn: "",
      toColumn: "",
      reverseLabel: "",
    };
  };

  return (
    <div className="px-6 py-5 max-md:px-4">
      <PageHeader
        title="本体建模"
        description="业务对象、信息项与业务关联；先建本体、再挂指标，是语义模型的建模顺序"
        extra={
          <Button size="small" variant="primary" onClick={openCreate}>
            新建{TABS.find((item) => item.key === tab)?.label}
          </Button>
        }
      />
      {optionsQuery.isError && <Alert className="mt-4">{failureMessage(optionsQuery.error)}</Alert>}
      {optionsQuery.isPending && <Spinner className="mt-6" />}
      {options && (
        <div className="mt-4 space-y-4">
          <div className="flex flex-wrap items-center gap-2">
            {TABS.map((item) => (
              <button
                key={item.key}
                type="button"
                onClick={() => {
                  setTab(item.key);
                  setSelectedId(null);
                  closeEditor();
                }}
                className={`rounded-[var(--radius)] border px-3 py-1.5 text-sm ${
                  tab === item.key
                    ? "border-[var(--primary)] bg-[var(--primary)] text-[var(--primary-foreground)]"
                    : "border-[var(--border)] text-[var(--muted-foreground)] hover:border-[var(--primary)]"
                }`}
              >
                {item.label}（{counts[item.key]}）
              </button>
            ))}
            <div className="ml-auto w-64 max-w-full">
              <Input
                size="small"
                variant="outlined"
                placeholder="按名称或 ID 搜索"
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
              />
            </div>
          </div>

          {editor && (
            <SectionCard
              title={
                editor.mode === "create"
                  ? `新建${TABS.find((item) => item.key === tab)?.label}草稿`
                  : `编辑草稿：${editor.draft?.id ?? ""}`
              }
            >
              {editorError && <Alert className="mb-3">{editorError}</Alert>}
              {tab === "concepts" && (
                <ConceptEditor
                  initial={editorInitial() as ConceptFormValues}
                  idLocked={editor.mode === "edit"}
                  options={options}
                  busy={busy}
                  onSave={(definition) => handleSave(definition, false)}
                  onValidate={handleValidate}
                  onSubmitReview={(definition) => handleSave(definition, true)}
                />
              )}
              {tab === "properties" && (
                <PropertyEditor
                  initial={editorInitial() as PropertyFormValues}
                  idLocked={editor.mode === "edit"}
                  options={options}
                  busy={busy}
                  onSave={(definition) => handleSave(definition, false)}
                  onValidate={handleValidate}
                  onSubmitReview={(definition) => handleSave(definition, true)}
                />
              )}
              {tab === "relations" && (
                <RelationEditor
                  initial={editorInitial() as RelationFormValues}
                  idLocked={editor.mode === "edit"}
                  options={options}
                  busy={busy}
                  onSave={(definition) => handleSave(definition, false)}
                  onValidate={handleValidate}
                  onSubmitReview={(definition) => handleSave(definition, true)}
                />
              )}
              {validation && (
                <div className="mt-3 rounded-[var(--radius)] border border-[var(--border)] p-3 text-xs">
                  <div className="font-medium">
                    合并视图校验：{validation.valid ? "通过" : "未通过"}
                  </div>
                  {(validation.violations ?? []).map((violation, index) => (
                    <div key={index} className="mt-1 text-[var(--muted-foreground)]">
                      {violation.location}：{violation.message}
                    </div>
                  ))}
                </div>
              )}
            </SectionCard>
          )}

          <div className="grid items-start gap-4 lg:grid-cols-3">
            <SectionCard
              title={`${TABS.find((item) => item.key === tab)?.label}（${rows.length}）`}
              className="lg:col-span-2"
            >
              {rows.length === 0 ? (
                <p className="text-sm text-[var(--muted-foreground)]">没有匹配的资产。</p>
              ) : (
                <div className="overflow-x-auto rounded-[var(--radius)] border border-[var(--border)]">
                  <table className="w-full text-sm">
                    <thead className="text-left text-xs text-[var(--muted-foreground)]">
                      <tr className="border-b border-[var(--border)]">
                        {COLUMNS[tab].map((column) => (
                          <th key={column} className="px-3 py-2 font-medium">
                            {column}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((row) => (
                        <tr
                          key={row.id}
                          onClick={() => setSelectedId(row.id)}
                          className={`cursor-pointer border-b border-[var(--border)] last:border-0 hover:bg-[var(--accent)] ${
                            selectedId === row.id ? "bg-[var(--accent)]" : ""
                          }`}
                        >
                          <td className="px-3 py-2">{row.name}</td>
                          {row.cells.map((cell, index) => (
                            <td
                              key={`${row.id}-${index}`}
                              className="px-3 py-2 text-[var(--muted-foreground)]"
                            >
                              {cell}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </SectionCard>

            <SectionCard title="详情">
              {selected ? (
                <>
                  <dl className="space-y-3 text-sm">
                    <div>
                      <dt className="text-xs text-[var(--muted-foreground)]">业务名</dt>
                      <dd className="mt-0.5">{selected.name}</dd>
                    </div>
                    <div>
                      <dt className="text-xs text-[var(--muted-foreground)]">资产 ID</dt>
                      <dd className="mt-0.5 font-mono text-xs">{selected.id}</dd>
                    </div>
                    <div>
                      <dt className="text-xs text-[var(--muted-foreground)]">说明</dt>
                      <dd className="mt-0.5 text-[var(--muted-foreground)]">
                        {selected.summary || "—"}
                      </dd>
                    </div>
                    {selected.cells.map((cell, index) => (
                      <div key={`detail-${index}`}>
                        <dt className="text-xs text-[var(--muted-foreground)]">
                          {COLUMNS[tab][index + 1]}
                        </dt>
                        <dd className="mt-0.5">{cell}</dd>
                      </div>
                    ))}
                  </dl>
                  <div className="mt-4 border-t border-[var(--border)] pt-3">
                    <Button size="small" disabled={busy} onClick={() => void handleRevise()}>
                      修订为草稿
                    </Button>
                    <p className="mt-1.5 text-xs text-[var(--muted-foreground)]">
                      以生效清单中的定义回迁为草稿，编辑器随后按清单内容预填。
                    </p>
                  </div>
                </>
              ) : (
                <p className="text-sm text-[var(--muted-foreground)]">选择左侧任意一行查看详情。</p>
              )}
            </SectionCard>
          </div>

          <SectionCard title={`草稿与审核中（${drafts.length}）`}>
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <p className="text-xs text-[var(--muted-foreground)]">
                合成发布会把全部类型中处于「审核中」的资产合并进新清单并激活（含跨草稿引用的成批校验）。
              </p>
              <Button
                size="small"
                variant="primary"
                loading={busy}
                onClick={() => void handleSynthesize()}
              >
                合成发布
              </Button>
            </div>
            {!editor && editorError && <Alert className="mb-3">{editorError}</Alert>}
            {draftsQuery.isPending && <Spinner className="my-2" />}
            {draftsQuery.isError && <Alert>{failureMessage(draftsQuery.error)}</Alert>}
            {drafts.length === 0 ? (
              <p className="text-sm text-[var(--muted-foreground)]">
                当前类型没有草稿；用右上角「新建」创建。
              </p>
            ) : (
              <div className="overflow-x-auto rounded-[var(--radius)] border border-[var(--border)]">
                <table className="w-full text-sm">
                  <thead className="text-left text-xs text-[var(--muted-foreground)]">
                    <tr className="border-b border-[var(--border)]">
                      <th className="px-3 py-2 font-medium">资产</th>
                      <th className="px-3 py-2 font-medium">ID</th>
                      <th className="px-3 py-2 font-medium">状态</th>
                      <th className="px-3 py-2 font-medium">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {drafts.map((draft) => (
                      <tr key={draft.id} className="border-b border-[var(--border)] last:border-0">
                        <td className="px-3 py-2">{draft.name}</td>
                        <td className="px-3 py-2 font-mono text-xs">{draft.id}</td>
                        <td className="px-3 py-2">
                          <Badge>{STATUS_LABEL[draft.status] ?? draft.status}</Badge>
                        </td>
                        <td className="px-3 py-2">
                          <div className="flex gap-2">
                            {draft.status === "draft" && (
                              <Button
                                size="small"
                                disabled={busy}
                                onClick={() => void openEdit(draft)}
                              >
                                编辑
                              </Button>
                            )}
                            {draft.status === "reviewing" && (
                              <Button
                                size="small"
                                disabled={busy}
                                onClick={() => void handleWithdraw(draft)}
                              >
                                撤回
                              </Button>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {synthesis && (
            <SectionCard title={`已发布：${synthesis.manifestId}`}>
              <dl className="grid gap-2 text-sm sm:grid-cols-2">
                <div>
                  <dt className="text-xs text-[var(--muted-foreground)]">contentHash</dt>
                  <dd className="font-mono text-xs">{synthesis.contentHash}</dd>
                </div>
                <div>
                  <dt className="text-xs text-[var(--muted-foreground)]">合成前清单</dt>
                  <dd className="font-mono text-xs">{synthesis.previousManifestId}</dd>
                </div>
                <div className="sm:col-span-2">
                  <dt className="text-xs text-[var(--muted-foreground)]">本次发布资产</dt>
                  <dd className="mt-1 flex flex-wrap gap-2">
                    {synthesis.published.map((asset) => (
                      <span
                        key={asset.id}
                        className="rounded border border-[var(--border)] px-2 py-0.5 font-mono text-xs"
                      >
                        {asset.assetType}/{asset.id}
                      </span>
                    ))}
                  </dd>
                </div>
                <div className="sm:col-span-2">
                  <dt className="text-xs text-[var(--muted-foreground)]">新清单资产计数</dt>
                  <dd>
                    {Object.entries(synthesis.assetCounts)
                      .map(([key, value]) => `${key}=${value}`)
                      .join("、")}
                  </dd>
                </div>
              </dl>
            </SectionCard>
          )}

          <SectionCard title="建模顺序与边界">
            <ol className="list-decimal space-y-1 pl-5 text-sm text-[var(--muted-foreground)]">
              <li>先在「数据源」确认物理表已被语义模型引用。</li>
              <li>再建业务对象与信息项（属性持有物理列，指标不直接绑表）。</li>
              <li>最后建指标与维度，引用对象属性；发布后进入问答链路。</li>
            </ol>
            <p className="mt-2 text-xs text-[var(--muted-foreground)]">
              草稿保存 / 校验 / 提交审核走 B1 资产接口，校验用「生效清单 + 待发布批次」合并视图；
              信息项与关联的物理列都从数据源插件的真实目录选择；已生效资产可「修订为草稿」后再编辑发布。
            </p>
          </SectionCard>
        </div>
      )}
    </div>
  );
}
