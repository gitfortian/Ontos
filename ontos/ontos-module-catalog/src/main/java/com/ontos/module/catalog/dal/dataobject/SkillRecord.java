package com.ontos.module.catalog.dal.dataobject;

import lombok.Data;

/**
 * Skill 生命周期记录（V15 skill_definition；M6/W3/T3.1）。
 * 列是状态/版本/owner/审核人的唯一真源，写库前同步进 definition（同 ScenarioRecord 纪律）。
 */
@Data
public class SkillRecord {
    private String id;
    private String name;
    private String summary;
    /** 大写枚举（QUERY / LOOKUP / ...，同列约束）。 */
    private String analysisMode;
    private String status;
    private String owner;
    /** 人工审核记录（published 必填，T3.1 特有）。 */
    private String reviewer;
    private String version;
    private String implementationRef;
    private String paramsSchemaRef;
    private java.util.List<String> requiredCapabilities;
    private java.util.List<String> allowedScenarioIds;
    private Integer maxBatchSize;
    private String approximationOf;
    private java.time.Instant publishedAt;
    private java.time.Instant updatedAt;
    /** 结构化 definition（ManifestPayload.SkillMeta），列值写前同步。 */
    private com.ontos.module.ontology.core.ManifestPayload.SkillMeta definition;
}
