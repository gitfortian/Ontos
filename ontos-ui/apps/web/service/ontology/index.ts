import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

import type {
  AssetsVO,
  DiffVO,
  ImpactVO,
  ManifestVO,
  PageResultVO,
  PathPlanVO,
  PublishRecordVO,
  SemanticAssetOptionsVO,
  SemanticAssetSummaryVO,
  SemanticAssetType,
  SemanticAssetVO,
  SemanticValidationVO,
  SemanticSynthesisVO,
} from "./types";

const ONTOLOGY_API = `${API_PREFIX}/ontology`;
const MANIFEST_API = `${ONTOLOGY_API}/manifests`;

/** Query cache identity for the active immutable manifest. */
export const manifestQueryKeys = {
  current: ["ontology", "manifest", "current"] as const,
};

export const ontologyQueryKeys = {
  assets: (manifestId?: string) => ["ontology", "assets", manifestId ?? "current"] as const,
  diff: (manifestId: string, baseId: string) => ["ontology", "diff", manifestId, baseId] as const,
  impact: (assetId: string) => ["ontology", "impact", assetId] as const,
  publishRecords: ["ontology", "publish-records"] as const,
  path: (from: string, to: string, domains: string) =>
    ["ontology", "paths", from, to, domains] as const,
};

export const getCurrentManifest = (signal?: AbortSignal): Promise<ManifestVO> =>
  HttpUtils.getData<ManifestVO>(`${MANIFEST_API}/current`, { signal });

export const getManifest = (manifestId: string, signal?: AbortSignal): Promise<ManifestVO> =>
  HttpUtils.getData<ManifestVO>(`${MANIFEST_API}/${encodeURIComponent(manifestId)}`, { signal });

export const getAssets = (
  manifestId: string | undefined,
  signal?: AbortSignal,
): Promise<AssetsVO> =>
  HttpUtils.getData<AssetsVO>(
    `${ONTOLOGY_API}/assets${manifestId ? `?manifestId=${encodeURIComponent(manifestId)}` : ""}`,
    { signal },
  );

export const diffManifests = (
  manifestId: string,
  baseId: string,
  signal?: AbortSignal,
): Promise<DiffVO> =>
  HttpUtils.getData<DiffVO>(
    `${MANIFEST_API}/${encodeURIComponent(manifestId)}/diff/${encodeURIComponent(baseId)}`,
    { signal },
  );

export const getImpact = (assetId: string, signal?: AbortSignal): Promise<ImpactVO> =>
  HttpUtils.getData<ImpactVO>(`${ONTOLOGY_API}/impact?assetId=${encodeURIComponent(assetId)}`, {
    signal,
  });

export const getPublishRecords = (
  limit: number,
  signal?: AbortSignal,
): Promise<PublishRecordVO[]> =>
  HttpUtils.getData<PublishRecordVO[]>(`${ONTOLOGY_API}/publish-records?limit=${limit}`, {
    signal,
  });

export const planPath = (
  from: string,
  to: string,
  domains: string,
  signal?: AbortSignal,
): Promise<PathPlanVO> =>
  HttpUtils.getData<PathPlanVO>(
    `${ONTOLOGY_API}/paths?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}` +
      `&domains=${encodeURIComponent(domains)}`,
    { signal },
  );

const CATALOG_API = `${API_PREFIX}/catalog/assets`;

export const semanticAssetQueryKeys = {
  options: ["catalog", "asset", "options"] as const,
  list: (
    type: SemanticAssetType,
    status: string,
    keyword: string,
    page: number,
    pageSize: number,
  ) => ["catalog", "asset", "list", type, status, keyword, page, pageSize] as const,
  detail: (type: SemanticAssetType, id: string) =>
    ["catalog", "asset", "detail", type, id] as const,
};

export const getSemanticAssetOptions = (signal?: AbortSignal): Promise<SemanticAssetOptionsVO> =>
  HttpUtils.getData<SemanticAssetOptionsVO>(`${CATALOG_API}/options`, { signal });

export const listSemanticAssets = (
  type: SemanticAssetType,
  params: { status?: string; keyword?: string; page?: number; pageSize?: number },
  signal?: AbortSignal,
): Promise<PageResultVO<SemanticAssetSummaryVO>> => {
  const query = new URLSearchParams();
  if (params.status) query.set("status", params.status);
  if (params.keyword) query.set("keyword", params.keyword);
  query.set("page", String(params.page ?? 1));
  query.set("pageSize", String(params.pageSize ?? 20));
  return HttpUtils.getData<PageResultVO<SemanticAssetSummaryVO>>(
    `${CATALOG_API}/${type}?${query.toString()}`,
    { signal },
  );
};

export const getSemanticAsset = (
  type: SemanticAssetType,
  id: string,
  signal?: AbortSignal,
): Promise<SemanticAssetVO> =>
  HttpUtils.getData<SemanticAssetVO>(`${CATALOG_API}/${type}/${encodeURIComponent(id)}`, {
    signal,
  });

export const createSemanticAssetDraft = (
  type: SemanticAssetType,
  definition: Record<string, unknown>,
): Promise<SemanticAssetVO> =>
  HttpUtils.postData<SemanticAssetVO>(`${CATALOG_API}/${type}/drafts`, { definition });

export const saveSemanticAssetDraft = (
  type: SemanticAssetType,
  id: string,
  definition: Record<string, unknown>,
  expectedRevision: number,
): Promise<SemanticAssetVO> =>
  HttpUtils.putData<SemanticAssetVO>(`${CATALOG_API}/${type}/${encodeURIComponent(id)}/draft`, {
    definition,
    expectedRevision,
  });

export const validateSemanticAsset = (
  type: SemanticAssetType,
  id: string,
  definition?: Record<string, unknown>,
): Promise<SemanticValidationVO> =>
  HttpUtils.postData<SemanticValidationVO>(
    `${CATALOG_API}/${type}/${encodeURIComponent(id)}/validate`,
    definition ? { definition } : {},
  );

export const submitSemanticAssetReview = (
  type: SemanticAssetType,
  id: string,
  expectedRevision: number,
): Promise<SemanticAssetVO> =>
  HttpUtils.postData<SemanticAssetVO>(
    `${CATALOG_API}/${type}/${encodeURIComponent(id)}/submit-review`,
    { expectedRevision },
  );

export const synthesizeSemanticAssets = (): Promise<SemanticSynthesisVO> =>
  HttpUtils.postData<SemanticSynthesisVO>(`${CATALOG_API}/synthesize`, {});

export const reviseSemanticAsset = (
  type: SemanticAssetType,
  id: string,
): Promise<SemanticAssetVO> =>
  HttpUtils.postData<SemanticAssetVO>(
    `${CATALOG_API}/${type}/${encodeURIComponent(id)}/revise`,
    {},
  );

export const withdrawSemanticAssetReview = (
  type: SemanticAssetType,
  id: string,
  expectedRevision: number,
): Promise<SemanticAssetVO> =>
  HttpUtils.postData<SemanticAssetVO>(
    `${CATALOG_API}/${type}/${encodeURIComponent(id)}/withdraw-review`,
    { expectedRevision },
  );

export type { ManifestVO, SemanticSynthesisVO } from "./types";
