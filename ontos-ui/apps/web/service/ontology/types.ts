export interface ManifestVO {
  manifestId: string;
  contentHash: string;
  ontologyVersion: string;
  semanticVersion: string;
  policyVersion: string;
  ruleVersion: string;
  timeParserVersion: string;
  copyTemplateVersion: string;
  createdAt?: string;
  metricIds: string[];
  conceptIds: string[];
}

/** 资产浏览 v2（按类型分组的 {id, name}，GET /ontology/assets；业务化命名规格 §4） */
export interface AssetsVO {
  manifestId?: string;
  contentHash?: string;
  empty?: boolean;
  metrics?: AssetChipVO[];
  dimensions?: AssetChipVO[];
  concepts?: AssetChipVO[];
  properties?: AssetChipVO[];
  relations?: AssetChipVO[];
  rules?: AssetChipVO[];
  scenarios?: AssetChipVO[];
}

/** 资产级 diff（GET /ontology/manifests/{id}/diff/{baseId}） */
export interface DiffVO {
  manifestId?: string;
  baseId?: string;
  empty?: boolean;
  reason?: string;
  metrics?: DiffSection;
  dimensions?: DiffSection;
  concepts?: DiffSection;
  relations?: DiffSection;
  rules?: DiffSection;
  scenarios?: DiffSection;
}

export interface DiffSection {
  added: string[];
  removed: string[];
}

/** 资产影响反查（GET /ontology/impact?assetId=） */
export interface ImpactVO {
  assetId: string;
  empty?: boolean;
  rules: string[];
  scenarios: string[];
  metricsAllowedDimensions: string[];
}

/** 发布历史（GET /ontology/publish-records） */
export interface PublishRecordVO {
  manifest_id: string;
  content_hash: string;
  dry_run: boolean;
  source_ref: string;
  published_by: string;
  published_at: string;
}

/** 路径规划结果（GET /ontology/paths） */
export interface PathPlanVO {
  status: "UNIQUE" | "AMBIGUOUS" | "NONE";
  candidates: Array<{
    edges: Array<{
      relationId: string;
      direction: "forward" | "reverse";
      from: string;
      to: string;
      cardinality: string;
    }>;
    hops: number;
    allGolden: boolean;
  }>;
  reason?: string;
}

/** 资产浏览 v2：每类返回 {id, name}（业务化命名规格 §4，界面展示业务名） */
export interface AssetChipVO {
  id: string;
  name: string;
}

/** 语义资产草稿（B1）：五类资产的编辑与生命周期。 */
export type SemanticAssetType = "concept" | "property" | "relation" | "metric" | "dimension";

export type SemanticAssetStatus = "draft" | "reviewing" | "published" | "retired";

export interface SemanticAssetOptionVO {
  id: string;
  name: string;
}

/** 业务对象：带领域、说明与属性数，供本体建模页展示。 */
export interface SemanticConceptOptionVO extends SemanticAssetOptionVO {
  domain?: string;
  summary?: string;
  propertyCount: number;
}

/** 属性选项带 conceptId：前端据此过滤「所选业务对象下的属性」。 */
export interface SemanticPropertyOptionVO extends SemanticAssetOptionVO {
  conceptId: string;
  valueType: string;
  summary?: string;
  filterable: boolean;
}

/** 业务关联：带起止对象，供本体建模页展示关系两端。 */
export interface SemanticRelationOptionVO extends SemanticAssetOptionVO {
  summary?: string;
  from: string;
  to: string;
}

/** GET /catalog/assets/options：编辑候选元数据（不返回物理列）。 */
export interface SemanticAssetOptionsVO {
  concepts: SemanticConceptOptionVO[];
  properties: SemanticPropertyOptionVO[];
  dimensions: SemanticAssetOptionVO[];
  metrics: SemanticAssetOptionVO[];
  relations: SemanticRelationOptionVO[];
}

export interface SemanticAssetSummaryVO {
  assetType: SemanticAssetType;
  id: string;
  name: string;
  summary: string;
  status: SemanticAssetStatus;
  owner: string;
  version: string;
  revision: number;
  updatedAt?: string;
}

export interface SemanticAssetVO extends SemanticAssetSummaryVO {
  reviewer?: string | null;
  definition: Record<string, unknown>;
  publishedAt?: string | null;
}

export interface SemanticViolationVO {
  location: string;
  message: string;
}

export interface SemanticValidationVO {
  valid: boolean;
  violations: SemanticViolationVO[];
}

export interface PageResultVO<T> {
  list: T[];
  total: number;
}

export interface SemanticSynthesisAssetVO {
  assetType: string;
  id: string;
}

/** POST /catalog/assets/synthesize：审核中批次 → 新生效清单。 */
export interface SemanticSynthesisVO {
  manifestId: string;
  contentHash: string;
  previousManifestId: string;
  published: SemanticSynthesisAssetVO[];
  assetCounts: Record<string, number>;
  invalidatedVqrs: string[];
}
