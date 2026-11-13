import { API_PREFIX } from "@/config/api";
import HttpUtils from "@/service/http/HttpUtils";

import type {
  DataSourceCatalogBrowseVO,
  DataSourceCatalogVO,
  DataSourceColumnVO,
  DataSourceConfigSaveVO,
  DataSourceConfigVO,
} from "./types";

const CATALOG_API = `${API_PREFIX}/catalog/data-sources`;

export const dataSourceQueryKeys = {
  catalog: ["catalog", "data-sources"] as const,
  browse: (target: string, schema: string) =>
    ["catalog", "data-sources", "browse", target, schema] as const,
  columns: (target: string, schema: string, table: string) =>
    ["catalog", "data-sources", "columns", target, schema, table] as const,
  configs: ["catalog", "data-sources", "configs"] as const,
};

export const getDataSourceCatalog = (signal?: AbortSignal): Promise<DataSourceCatalogVO> =>
  HttpUtils.getData<DataSourceCatalogVO>(CATALOG_API, { signal });

/** target = "current"（配置文件的当前查询库）或已注册配置 id。 */
const browsePath = (target: string) =>
  target === "current"
    ? `${CATALOG_API}/current/catalog`
    : `${CATALOG_API}/configs/${encodeURIComponent(target)}/catalog`;

const columnsPath = (target: string) =>
  target === "current"
    ? `${CATALOG_API}/current/columns`
    : `${CATALOG_API}/configs/${encodeURIComponent(target)}/columns`;

export const browseDataSourceCatalog = (
  schema: string | undefined,
  signal?: AbortSignal,
): Promise<DataSourceCatalogBrowseVO> =>
  HttpUtils.getData<DataSourceCatalogBrowseVO>(
    `${CATALOG_API}/current/catalog${schema ? `?schema=${encodeURIComponent(schema)}` : ""}`,
    { signal },
  );

export const browseDataSourceByTarget = (
  target: string,
  schema: string | undefined,
  signal?: AbortSignal,
): Promise<DataSourceCatalogBrowseVO> =>
  HttpUtils.getData<DataSourceCatalogBrowseVO>(
    `${browsePath(target)}${schema ? `?schema=${encodeURIComponent(schema)}` : ""}`,
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

export const getDataSourceColumnsByTarget = (
  target: string,
  schema: string,
  table: string,
  signal?: AbortSignal,
): Promise<DataSourceColumnVO[]> =>
  HttpUtils.getData<DataSourceColumnVO[]>(
    `${columnsPath(target)}?schema=${encodeURIComponent(schema)}&table=${encodeURIComponent(table)}`,
    { signal },
  );

export const testDataSourceConnection = (): Promise<boolean> =>
  HttpUtils.postData<boolean>(`${CATALOG_API}/current/test`, {});

/** 按表单值即时测试（不落库）：复用 POST /{type}/test，注册向导的「测试连接」按钮用。 */
export const testDataSourceConfigValues = (
  type: string,
  config: Record<string, string>,
): Promise<boolean> =>
  HttpUtils.postData<boolean>(`${CATALOG_API}/${encodeURIComponent(type)}/test`, config);

// —— 注册配置（B2 Phase 2 前半）——

export const listDataSourceConfigs = (signal?: AbortSignal): Promise<DataSourceConfigVO[]> =>
  HttpUtils.getData<DataSourceConfigVO[]>(`${CATALOG_API}/configs`, { signal });

export const createDataSourceConfig = (
  payload: DataSourceConfigSaveVO,
): Promise<DataSourceConfigVO> =>
  HttpUtils.postData<DataSourceConfigVO>(`${CATALOG_API}/configs`, payload);

export const updateDataSourceConfig = (
  id: string,
  payload: DataSourceConfigSaveVO,
): Promise<DataSourceConfigVO> =>
  HttpUtils.putData<DataSourceConfigVO>(
    `${CATALOG_API}/configs/${encodeURIComponent(id)}`,
    payload,
  );

export const deleteDataSourceConfig = (id: string): Promise<boolean> =>
  HttpUtils.deleteData<boolean>(`${CATALOG_API}/configs/${encodeURIComponent(id)}`);

export const testDataSourceConfig = (id: string): Promise<boolean> =>
  HttpUtils.postData<boolean>(`${CATALOG_API}/configs/${encodeURIComponent(id)}/test`, {});

export type {
  DataSourceCatalogBrowseVO,
  DataSourceCatalogVO,
  DataSourceColumnVO,
  DataSourceConfigFieldVO,
  DataSourceConfigSaveVO,
  DataSourceConfigVO,
  DataSourceManifestTableVO,
  DataSourcePluginVO,
} from "./types";
