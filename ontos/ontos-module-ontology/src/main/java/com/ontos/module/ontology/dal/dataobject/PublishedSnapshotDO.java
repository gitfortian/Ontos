package com.ontos.module.ontology.dal.dataobject;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 发布清单 DO。对应 System DB 的 {@code published_snapshot}，只 INSERT、不可变。
 */
@Data
public class PublishedSnapshotDO {

    private String manifestId;
    private String contentHash;
    private String ontologyVersion;
    private String semanticVersion;
    private String policyVersion;
    private String ruleVersion;
    private String timeParserVersion;
    private String copyTemplateVersion;
    /** 原始 payload JSON，哈希校验与审计回放都以原文为准。 */
    private String payload;
    private LocalDateTime createdAt;
    private String createdBy;

}
