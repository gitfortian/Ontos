package com.ontos.module.catalog.dal.dataobject;

import java.time.Instant;

import com.ontos.module.ontology.core.ManifestPayload;
import lombok.Data;

/**
 * Scenario 生命周期记录（M6/W2）：状态在列上，资产内容在 definition JSONB。
 *
 * <p>表只服务管理面；运行时永远读已发布清单的 scenarios 段，不读本表草稿。
 */
@Data
public class ScenarioRecord {

    private String id;
    private String name;
    private String summary;
    /** draft | reviewing | published | retired */
    private String status;
    private String owner;
    private String reviewer;
    private String version;
    /** manual_only | recommend_or_confirm | auto_if_confident */
    private String entryMode;
    private ManifestPayload.ScenarioMeta definition;
    private Instant publishedAt;
    private Instant updatedAt;

    public boolean isPublished() {
        return "published".equals(status);
    }
}
