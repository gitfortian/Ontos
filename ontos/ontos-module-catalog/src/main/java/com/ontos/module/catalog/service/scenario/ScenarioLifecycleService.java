package com.ontos.module.catalog.service.scenario;

import java.time.Instant;
import java.util.List;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.catalog.core.CatalogAssetStatus;
import com.ontos.module.catalog.dal.dataobject.ScenarioRecord;
import com.ontos.module.catalog.dal.mysql.ScenarioRepository;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.ManifestStructureValidator;
import com.ontos.module.ontology.core.ScenarioValidator;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_ALREADY_EXISTS;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_INVALID;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_MANIFEST_MISSING;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_NOT_FOUND;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_REVIEW_REQUIRED;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_STATE_INVALID;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_VERSION_INVALID;

/**
 * Scenario 资产生命周期（M6/W2/T2.2）：draft → reviewing → published → retired。
 *
 * <p>发布前做两件事：① 用 {@link ScenarioValidator} 对**目标清单**逐条校验（引用/scope/口径/版本）；
 * ② 版本兼容与依赖缺失阻断，不静默降级。
 *
 * <p>回滚不新增状态：从已发布清单取旧版本内容，生成**新补丁版本 draft**，再走正常审核/发布；
 * 运行时在清单重新发布前仍读旧清单，管理表状态变化不影响线上。
 */
@Service
public class ScenarioLifecycleService {

    private final ScenarioRepository repository;
    private final ScenarioValidator validator = new ScenarioValidator();
    private final ScenarioDependencyAnalyzer dependencyAnalyzer;

    public ScenarioLifecycleService(ScenarioRepository repository,
                                    ScenarioDependencyAnalyzer dependencyAnalyzer) {
        this.repository = repository;
        this.dependencyAnalyzer = dependencyAnalyzer;
    }

    /** 新建草稿；版本缺省 0.1.0，owner 缺省为操作人。 */
    public ScenarioRecord createDraft(ManifestPayload.ScenarioMeta definition, String actor) {
        requireDefinition(definition);
        if (repository.findById(definition.getId()).isPresent()) {
            throw exception(SCENARIO_ALREADY_EXISTS, definition.getId());
        }
        ManifestPayload.ScenarioMeta copy = copy(definition);
        ScenarioRecord record = new ScenarioRecord();
        record.setId(copy.getId());
        record.setName(copy.getName());
        record.setSummary(copy.getSummary());
        record.setOwner(blank(copy.getOwner()) ? actor : copy.getOwner());
        record.setVersion(blank(copy.getVersion()) ? "0.1.0" : copy.getVersion());
        record.setEntryMode(copy.getEntryMode());
        record.setStatus(CatalogAssetStatus.DRAFT.code());
        record.setDefinition(copy);
        syncDefinition(record);
        repository.insert(record);
        return record;
    }

    /** 提交审核：draft → reviewing。 */
    public ScenarioRecord submitForReview(String id) {
        ScenarioRecord record = require(id);
        transition(record, CatalogAssetStatus.REVIEWING);
        persist(record);
        return record;
    }

    /** 人工审核：通过则记录审核人（仍为 reviewing，等待发布）；驳回退回 draft。 */
    public ScenarioRecord review(String id, String reviewer, boolean approved) {
        ScenarioRecord record = require(id);
        requireStatus(record, CatalogAssetStatus.REVIEWING);
        if (approved) {
            if (blank(reviewer)) {
                throw exception(SCENARIO_REVIEW_REQUIRED, id);
            }
            record.setReviewer(reviewer);
        } else {
            record.setReviewer(null);
            transition(record, CatalogAssetStatus.DRAFT);
        }
        persist(record);
        return record;
    }

    /**
     * 发布：必须已审核 + 目标清单校验通过；状态转 published 并记录发布时间。
     *
     * @param targetPayload 即将发布的清单 payload（调用方保证是同一发布事务的目标版本）
     */
    public ScenarioRecord publish(String id, ManifestPayload targetPayload) {
        ScenarioRecord record = require(id);
        requireStatus(record, CatalogAssetStatus.REVIEWING);
        if (record.getDefinition() == null || !id.equals(record.getDefinition().getId())) {
            throw exception(SCENARIO_INVALID, "记录 ID 与 definition.id 不一致: " + id);
        }
        if (blank(record.getReviewer())) {
            throw exception(SCENARIO_REVIEW_REQUIRED, id);
        }
        if (targetPayload == null) {
            throw exception(SCENARIO_MANIFEST_MISSING);
        }
        List<ManifestStructureValidator.Violation> violations =
                validator.validateOne(record.getDefinition(), targetPayload, ScenarioValidator.PublishedRefs.empty());
        if (!violations.isEmpty()) {
            throw exception(SCENARIO_INVALID, violations.toString());
        }
        java.util.Set<String> missing = dependencyAnalyzer.missingReferences(record.getDefinition(), targetPayload);
        if (!missing.isEmpty()) {
            throw exception(SCENARIO_INVALID, "引用目标清单中不存在的资产: " + missing);
        }
        transition(record, CatalogAssetStatus.PUBLISHED);
        record.setPublishedAt(Instant.now());
        persist(record);
        return record;
    }

    /** 下架：published → retired；保留发布时间供审计回放。 */
    public ScenarioRecord retire(String id) {
        ScenarioRecord record = require(id);
        transition(record, CatalogAssetStatus.RETIRED);
        persist(record);
        return record;
    }

    /**
     * 回滚：从已发布清单取旧版本内容，生成补丁版本草稿（不直接改线上清单）。
     * 版本号取旧版本 patch+1，重新走审核/发布；旧版本内容保持不变。
     */
    public ScenarioRecord rollback(String id, ManifestPayload.ScenarioMeta publishedSnapshot, String actor) {
        ScenarioRecord current = require(id);
        CatalogAssetStatus currentStatus = CatalogAssetStatus.fromCode(current.getStatus());
        if (currentStatus != CatalogAssetStatus.PUBLISHED && currentStatus != CatalogAssetStatus.RETIRED) {
            throw exception(SCENARIO_STATE_INVALID, currentStatus.code(), "rollback");
        }
        if (publishedSnapshot == null || !id.equals(publishedSnapshot.getId())) {
            throw exception(SCENARIO_VERSION_INVALID, "回滚快照与资产 ID 不一致: " + id);
        }
        String nextVersion = nextPatchVersion(publishedSnapshot.getVersion());
        ManifestPayload.ScenarioMeta copy = copy(publishedSnapshot);
        current.setStatus(CatalogAssetStatus.DRAFT.code());
        current.setVersion(nextVersion);
        current.setReviewer(null);
        current.setPublishedAt(null);
        current.setOwner(blank(publishedSnapshot.getOwner()) ? actor : publishedSnapshot.getOwner());
        current.setName(copy.getName());
        current.setSummary(copy.getSummary());
        current.setEntryMode(copy.getEntryMode());
        current.setDefinition(copy);
        persist(current);
        return current;
    }

    /** 资产影响反查：哪些已发布 Scenario 引用了该资产。 */
    public List<String> impact(String assetId) {
        return dependencyAnalyzer.impactedScenarios(assetId, repository.findAll());
    }

    /** 废弃一组资产前的影响面（阻断判断由调用方决定）。 */
    public List<String> impact(java.util.Set<String> assetIds) {
        return dependencyAnalyzer.impactedScenarios(assetIds, repository.findAll());
    }

    static String nextPatchVersion(String version) {
        if (version == null || version.isBlank()) {
            return "0.1.0";
        }
        String[] parts = version.split("\\.");
        if (parts.length == 3 && parts[0].matches("\\d+") && parts[1].matches("\\d+")
                && parts[2].matches("\\d+")) {
            return parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
        }
        throw exception(SCENARIO_VERSION_INVALID, "回滚需要 SemVer 版本号: " + version);
    }

    private void persist(ScenarioRecord record) {
        syncDefinition(record);
        repository.update(record);
    }

    /** 列是唯一真源：每次写库前把状态/版本/owner/entryMode 同步进 definition，避免两份值漂移。 */
    private void syncDefinition(ScenarioRecord record) {
        ManifestPayload.ScenarioMeta definition = record.getDefinition();
        if (definition == null) {
            return;
        }
        definition.setStatus(record.getStatus());
        definition.setVersion(record.getVersion());
        definition.setOwner(record.getOwner());
        definition.setEntryMode(record.getEntryMode());
    }

    private ScenarioRecord require(String id) {
        return repository.findById(id)
                .orElseThrow(() -> exception(SCENARIO_NOT_FOUND, id));
    }

    private void transition(ScenarioRecord record, CatalogAssetStatus target) {
        CatalogAssetStatus current = CatalogAssetStatus.fromCode(record.getStatus());
        if (!current.canTransitionTo(target)) {
            throw exception(SCENARIO_STATE_INVALID, current.code(), target.code());
        }
        record.setStatus(target.code());
    }

    private void requireStatus(ScenarioRecord record, CatalogAssetStatus expected) {
        if (!expected.code().equals(record.getStatus())) {
            throw exception(SCENARIO_STATE_INVALID, record.getStatus(), expected.code());
        }
    }

    private void requireDefinition(ManifestPayload.ScenarioMeta definition) {
        if (definition == null || blank(definition.getId()) || blank(definition.getName())) {
            throw exception(SCENARIO_INVALID, "id / name 必填");
        }
    }

    private ManifestPayload.ScenarioMeta copy(ManifestPayload.ScenarioMeta definition) {
        return JsonUtils.parseObject(JsonUtils.toJsonString(definition),
                ManifestPayload.ScenarioMeta.class);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
