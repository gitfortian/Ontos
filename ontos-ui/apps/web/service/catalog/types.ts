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
