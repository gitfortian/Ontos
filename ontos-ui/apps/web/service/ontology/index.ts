import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

import type { AssetsVO, DiffVO, ImpactVO, ManifestVO, PathPlanVO, PublishRecordVO } from "./types";

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

export type { ManifestVO } from "./types";
