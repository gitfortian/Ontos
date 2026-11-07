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
