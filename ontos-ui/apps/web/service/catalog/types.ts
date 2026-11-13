/** 数据源目录（B2）：当前查询库 + 插件 + 真实库目录。 */

export interface DataSourcePluginVO {
  type: string;
  displayName: string;
  capabilities: string[];
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
