package com.ontos.module.datasource.dal.dataobject;

import java.time.OffsetDateTime;

/** 数据视图资产（B3）：扫描固化的列快照；建模经 Property.viewId 引用，物理表名只在 sourceTable。 */
public class DataViewRecord {

    private String id;
    private String name;
    /** 来源注册数据源 id；null = 当前查询库（Environment 配置）。 */
    private String sourceConfigId;
    private String sourceSchema;
    private String sourceTable;
    /** 列快照 JSON 数组字符串：[{"name":..,"type":..,"nullable":..}]。 */
    private String columnsJson;
    /** 列集合+类型 SHA-256（漂移检测）。 */
    private String contentHash;
    private String status;
    private String createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceConfigId() {
        return sourceConfigId;
    }

    public void setSourceConfigId(String sourceConfigId) {
        this.sourceConfigId = sourceConfigId;
    }

    public String getSourceSchema() {
        return sourceSchema;
    }

    public void setSourceSchema(String sourceSchema) {
        this.sourceSchema = sourceSchema;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String getColumnsJson() {
        return columnsJson;
    }

    public void setColumnsJson(String columnsJson) {
        this.columnsJson = columnsJson;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
