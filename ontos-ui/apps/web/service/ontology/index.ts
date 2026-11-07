import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

import type { ManifestVO } from "./types";

const MANIFEST_API = `${API_PREFIX}/ontology/manifests`;

/** Query cache identity for the active immutable manifest. */
export const manifestQueryKeys = {
  current: ["ontology", "manifest", "current"] as const,
};

export const getCurrentManifest = (signal?: AbortSignal): Promise<ManifestVO> =>
  HttpUtils.getData<ManifestVO>(`${MANIFEST_API}/current`, { signal });

export const getManifest = (manifestId: string, signal?: AbortSignal): Promise<ManifestVO> =>
  HttpUtils.getData<ManifestVO>(`${MANIFEST_API}/${encodeURIComponent(manifestId)}`, { signal });

export type { ManifestVO } from "./types";
