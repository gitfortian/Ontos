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

/** 资产浏览（按类型分组的 ID 清单，GET /ontology/assets） */
export interface AssetsVO {
  manifestId?: string;
  contentHash?: string;
  empty?: boolean;
  metrics?: string[];
  dimensions?: string[];
  concepts?: string[];
  properties?: string[];
  relations?: string[];
  rules?: string[];
  scenarios?: string[];
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
