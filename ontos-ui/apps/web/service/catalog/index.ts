import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

import type { DataSourceCatalogBrowseVO, DataSourceCatalogVO, DataSourceColumnVO } from "./types";

const CATALOG_API = `${API_PREFIX}/catalog/data-sources`;

export const dataSourceQueryKeys = {
  catalog: ["catalog", "data-sources"] as const,
  browse: (schema: string) => ["catalog", "data-sources", "browse", schema] as const,
  columns: (schema: string, table: string) =>
    ["catalog", "data-sources", "columns", schema, table] as const,
};

export const getDataSourceCatalog = (signal?: AbortSignal): Promise<DataSourceCatalogVO> =>
  HttpUtils.getData<DataSourceCatalogVO>(CATALOG_API, { signal });

export const browseDataSourceCatalog = (
  schema: string | undefined,
  signal?: AbortSignal,
): Promise<DataSourceCatalogBrowseVO> =>
  HttpUtils.getData<DataSourceCatalogBrowseVO>(
    `${CATALOG_API}/current/catalog${schema ? `?schema=${encodeURIComponent(schema)}` : ""}`,
    { signal },
  );

export const getDataSourceColumns = (
  schema: string,
  table: string,
  signal?: AbortSignal,
): Promise<DataSourceColumnVO[]> =>
  HttpUtils.getData<DataSourceColumnVO[]>(
    `${CATALOG_API}/current/columns?schema=${encodeURIComponent(schema)}&table=${encodeURIComponent(table)}`,
    { signal },
  );

export const testDataSourceConnection = (): Promise<boolean> =>
  HttpUtils.postData<boolean>(`${CATALOG_API}/current/test`, {});

export type {
  DataSourceCatalogBrowseVO,
  DataSourceCatalogVO,
  DataSourceColumnVO,
  DataSourceManifestTableVO,
  DataSourcePluginVO,
} from "./types";
