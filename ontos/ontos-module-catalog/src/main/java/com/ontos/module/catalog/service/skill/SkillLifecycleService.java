package com.ontos.module.catalog.service.skill;

import java.time.Instant;
import java.util.List;

import com.ontos.module.catalog.core.CatalogAssetStatus;
import com.ontos.module.catalog.dal.dataobject.SkillRecord;
import com.ontos.module.catalog.dal.mysql.JdbcSkillRepository;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.ManifestStructureValidator;
import com.ontos.module.ontology.core.SkillValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_INVALID;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_REVIEW_REQUIRED;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_STATE_INVALID;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_VERSION_INVALID;

/**
 * Skill 生命周期（v1.4 §5.8，M6/W3/T3.1）：draft → reviewing → published → retired，
 * 与 ScenarioLifecycleService 同一套状态机纪律；**review 动作记录审核人**（T3.1 特有：
 * reviewer 是人工审核留痕，published 必有）。
 *
 * <p>发布闸门：目标清单上下文内跑单 Skill 校验（{@link SkillValidator#validateOne}）；
 * 回滚从不可变清单快照生成补丁版本草稿。错误码复用 catalog 段（SCENARIO_* 语义同族）。
 */
@Slf4j
@Service
public class SkillLifecycleService {

    private final JdbcSkillRepository repository;
    private final SkillValidator skillValidator = new SkillValidator();

    public SkillLifecycleService(JdbcSkillRepository repository) {
        this.repository = repository;
    }

    /** 创建草稿：definition 里的结构化字段回填到列（analysisMode / implementationRef 等）。 */
    public SkillRecord createDraft(ManifestPayload.SkillMeta definition) {
        List<ManifestStructureValidator.Violation> violations =
                skillValidator.validateOne(definition, emptyPayload());
        if (!violations.isEmpty()) {
            throw exception(SCENARIO_INVALID, "Skill 结构校验失败: " + violations);
        }
        SkillRecord record = new SkillRecord();
        record.setId(definition.getSkillId());
        record.setName(definition.getName());
        record.setSummary(definition.getName());
        record.setAnalysisMode(definition.getAnalysisMode());
        record.setStatus(CatalogAssetStatus.DRAFT.code());
        record.setOwner(definition.getOwner());
        record.setVersion(definition.getVersion());
        record.setImplementationRef(definition.getImplementationRef());
        record.setParamsSchemaRef(definition.getParamsSchemaRef());
        record.setRequiredCapabilities(definition.getRequiredCapabilities());
        record.setAllowedScenarioIds(definition.getAllowedScenarioIds());
        record.setMaxBatchSize(definition.getMaxBatchSize());
        record.setApproximationOf(definition.getApproximationOf());
        record.setDefinition(definition);
        repository.insert(record);
        return record;
    }

    /** 提交审核：draft → reviewing。 */
    public SkillRecord submitForReview(String id) {
        SkillRecord record = require(id);
        transition(record, CatalogAssetStatus.REVIEWING);
        persist(record);
        return record;
    }

    /**
     * 人工审核（T3.1 特有）：reviewing 状态下记录审核人；reviewer 写入列与 definition（发布闸门校验
     * published 必有 reviewer）。审核不通过走 {@link #reject} 退回草稿。
     */
    public SkillRecord approve(String id, String reviewer) {
        if (reviewer == null || reviewer.isBlank()) {
            throw exception(SCENARIO_INVALID, "审核人不能为空");
        }
        SkillRecord record = require(id);
        requireStatus(record, CatalogAssetStatus.REVIEWING);
        if (record.getDefinition() == null || !id.equals(record.getDefinition().getSkillId())) {
            throw exception(SCENARIO_INVALID, "记录 ID 与 definition.skillId 不一致: " + id);
        }
        // 审核人记录是审核动作的留痕，不是状态迁移（REVIEWING → REVIEWING 不在封闭迁移表内）
        record.setReviewer(reviewer);
        persist(record);
        log.info("[approve][skill({}) reviewer({})]", id, reviewer);
        return record;
    }

    /** 审核不通过：reviewing → draft（reviewer 记录保留，供追溯）。 */
    public SkillRecord reject(String id) {
        SkillRecord record = require(id);
        requireStatus(record, CatalogAssetStatus.REVIEWING);
        transition(record, CatalogAssetStatus.DRAFT);
        persist(record);
        return record;
    }

    /** 发布（reviewing 且已有审核人）：目标清单上下文内单 Skill 校验通过才放行。 */
    public SkillRecord publish(String id, ManifestPayload targetPayload) {
        SkillRecord record = require(id);
        requireStatus(record, CatalogAssetStatus.REVIEWING);
        if (record.getReviewer() == null || record.getReviewer().isBlank()) {
            throw exception(SCENARIO_REVIEW_REQUIRED, id);
        }
        if (record.getDefinition() == null || !id.equals(record.getDefinition().getSkillId())) {
            throw exception(SCENARIO_INVALID, "记录 ID 与 definition.skillId 不一致: " + id);
        }
        List<ManifestStructureValidator.Violation> violations =
                skillValidator.validateOne(record.getDefinition(), targetPayload);
        if (!violations.isEmpty()) {
            throw exception(SCENARIO_INVALID, "目标清单校验失败: " + violations);
        }
        transition(record, CatalogAssetStatus.PUBLISHED);
        record.setPublishedAt(Instant.now());
        persist(record);
        return record;
    }

    /** 下线（published → retired；published_at 保留供审计回放）。 */
    public SkillRecord retire(String id) {
        SkillRecord record = require(id);
        requireStatus(record, CatalogAssetStatus.PUBLISHED);
        transition(record, CatalogAssetStatus.RETIRED);
        persist(record);
        return record;
    }

    /**
     * 回滚 = 从不可变清单快照生成补丁版本草稿（版本号必须 SemVer；rollback 版本 = patch+1 草稿）。
     * 只允许 published / retired 记录回滚（draft/reviewing 没有已发布版本可回）。
     */
    public SkillRecord rollback(String id, ManifestPayload.SkillMeta snapshot, String operator) {
        SkillRecord current = require(id);
        CatalogAssetStatus currentStatus = CatalogAssetStatus.fromCode(current.getStatus());
        if (currentStatus != CatalogAssetStatus.PUBLISHED && currentStatus != CatalogAssetStatus.RETIRED) {
            throw exception(SCENARIO_STATE_INVALID, currentStatus.code(), "rollback");
        }
        if (snapshot == null || !id.equals(snapshot.getSkillId())) {
            throw exception(SCENARIO_INVALID, "回滚快照与记录不一致: " + id);
        }
        if (snapshot.getVersion() == null || !snapshot.getVersion().matches("\\d+\\.\\d+\\.\\d+")) {
            throw exception(SCENARIO_VERSION_INVALID, "回滚需要 SemVer 版本号: " + snapshot.getVersion());
        }
        String[] parts = snapshot.getVersion().split("\\.");
        String nextVersion = parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
        snapshot.setStatus(CatalogAssetStatus.DRAFT.code());
        snapshot.setVersion(nextVersion);
        snapshot.setOwner(operator == null || operator.isBlank()
                ? current.getOwner() : operator);
        SkillRecord record = new SkillRecord();
        record.setId(snapshot.getSkillId());
        record.setName(snapshot.getName());
        record.setSummary(snapshot.getName());
        record.setAnalysisMode(snapshot.getAnalysisMode());
        record.setStatus(CatalogAssetStatus.DRAFT.code());
        record.setOwner(snapshot.getOwner());
        record.setVersion(nextVersion);
        record.setImplementationRef(snapshot.getImplementationRef());
        record.setParamsSchemaRef(snapshot.getParamsSchemaRef());
        record.setRequiredCapabilities(snapshot.getRequiredCapabilities());
        record.setAllowedScenarioIds(snapshot.getAllowedScenarioIds());
        record.setMaxBatchSize(snapshot.getMaxBatchSize());
        record.setApproximationOf(snapshot.getApproximationOf());
        record.setDefinition(snapshot);
        repository.insert(record);
        return record;
    }

    private void transition(SkillRecord record, CatalogAssetStatus target) {
        CatalogAssetStatus current = CatalogAssetStatus.fromCode(record.getStatus());
        if (!current.canTransitionTo(target)) {
            throw exception(SCENARIO_STATE_INVALID, current.code(), target.code());
        }
        record.setStatus(target.code());
    }

    private void requireStatus(SkillRecord record, CatalogAssetStatus expected) {
        CatalogAssetStatus current = CatalogAssetStatus.fromCode(record.getStatus());
        if (current != expected) {
            throw exception(SCENARIO_STATE_INVALID, current.code(), expected.code());
        }
    }

    private SkillRecord require(String id) {
        return repository.findById(id)
                .orElseThrow(() -> exception(SCENARIO_INVALID, "Skill 不存在: " + id));
    }

    private void persist(SkillRecord record) {
        repository.update(record);
    }

    private ManifestPayload emptyPayload() {
        ManifestPayload payload = new ManifestPayload();
        payload.setScenarios(List.of());
        payload.setSkills(List.of());
        return payload;
    }

}
