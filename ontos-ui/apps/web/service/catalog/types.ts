/** 数据源目录（B2）：当前查询库 + 插件 + 真实库目录 + 注册配置（B2 Phase 2 前半）。 */

export interface DataSourceConfigFieldVO {
  key: string;
  label: string;
  required: boolean;
  secret: boolean;
  placeholder?: string | null;
}

export interface DataSourcePluginVO {
  type: string;
  displayName: string;
  capabilities: string[];
  configFields: DataSourceConfigFieldVO[];
}

export interface DataSourceManifestTableVO {
  name: string;
  propertyCount: number;
  propertyIds: string[];
}

export interface DataSourceCatalogVO {
  id: string;
  name: string;
  jdbcUrl: string;
  readOnly: boolean;
  schema: string;
  manifestId?: string | null;
  manifestTables: DataSourceManifestTableVO[];
  plugins: DataSourcePluginVO[];
}

export interface DataSourceCatalogTableVO {
  name: string;
  type: string;
}

export interface DataSourceCatalogBrowseVO {
  type: string;
  schemas: string[];
  schema: string;
  tables: DataSourceCatalogTableVO[];
}

export interface DataSourceColumnVO {
  name: string;
  dataType: string;
  nullable: boolean;
}

/** 注册数据源配置（脱敏视图）：secret 字段值为「••••」掩码。 */
export interface DataSourceConfigVO {
  id: string;
  name: string;
  type: string;
  config: Record<string, string>;
  hasSecret: boolean;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** 注册/编辑请求；secrets 缺省 = 编辑时保留原凭据。 */
export interface DataSourceConfigSaveVO {
  name: string;
  type?: string;
  config: Record<string, string>;
  secrets?: Record<string, string>;
}

/** 数据视图列快照项（B3）。 */
export interface DataViewColumnVO {
  name: string;
  type: string;
  nullable: boolean;
}

/** 数据视图资产（扫描固化；建模经 viewId 引用）。 */
export interface DataViewVO {
  id: string;
  name: string;
  sourceConfigId?: string | null;
  sourceSchema: string;
  sourceTable: string;
  columns: DataViewColumnVO[];
  contentHash: string;
  status: "active" | "retired";
  createdBy?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface DataViewDiffVO {
  addedColumns: string[];
  removedColumns: string[];
  typeChangedColumns: string[];
  contentChanged: boolean;
}

/** 扫描/重扫描结果：视图 + 漂移 diff + 被生效清单引用的消失列（管理面告警依据）。 */
export interface DataViewScanVO {
  view: DataViewVO;
  diff: DataViewDiffVO;
  referencedRemoved: string[];
}

export interface DataViewScanReqVO {
  configId?: string | null;
  schema: string;
  table: string;
  name?: string;
  /** 视图 ID 手改（B3 §3.1）：留空 = 按物理名规范化生成；仅新视图生效，覆盖路径忽略。 */
  requestedId?: string;
}
