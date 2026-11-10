package com.ontos.module.catalog.dal.dataobject;

import lombok.Data;

/**
 * VQR 生命周期记录（V15 vqr_definition；M6/W3/T3.4）。
 * 列是状态/版本/owner/审核人/新鲜度/证据引用的唯一真源，写库前同步进 definition（同 SkillRecord 纪律）。
 */
@Data
public class VqrRecord {
    private String id;
    private String name;
    private String summary;
    private String status;
    private String owner;
    /** 人工审核记录（published 必填）。 */
    private String reviewer;
    private String version;
    /** 问题模式（候选匹配用）。 */
    private String questionPattern;
    /** 参数化 DSL 模板（JSONB object；只引用资产 ID，不存可执行 SQL）。 */
    private java.util.Map<String, Object> dslTemplate;
    /** 参数 Schema（JSONB object）。 */
    private java.util.Map<String, Object> paramsSchema;
    /** Golden 证据引用（D4 外置：catalog 不连库复验）。 */
    private String goldenRef;
    /** 结果校验证据引用（D4 外置，含目标库执行/权限测试/结果对题）。 */
    private String resultCheckRef;
    /** 数据新鲜度上限（小时）。 */
    private Integer freshnessLimitHours;
    /** 声明式失效条件（JSONB array：{assetId, trigger}）。 */
    private java.util.List<java.util.Map<String, Object>> invalidationConditions;
    private java.time.Instant publishedAt;
    private java.time.Instant updatedAt;
    /** 结构化 definition（ManifestPayload.VqrMeta），列值写前同步。 */
    private com.ontos.module.ontology.core.ManifestPayload.VqrMeta definition;
}
