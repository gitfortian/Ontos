package com.ontos.module.catalog.service.vqr;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ontos.module.catalog.core.CatalogAssetStatus;
import com.ontos.module.catalog.dal.dataobject.VqrRecord;
import com.ontos.module.catalog.dal.mysql.JdbcVqrRepository;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.ManifestStructureValidator;
import com.ontos.module.ontology.core.VqrInvalidationAnalyzer;
import com.ontos.module.ontology.core.VqrValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_INVALID;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_REVIEW_REQUIRED;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_STATE_INVALID;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.SCENARIO_VERSION_INVALID;

/**
 * VQR 生命周期（v1.4 §11.1，M6/W3/T3.4）：候选 → 草稿 → 自动校验 → 人工审核 → 发布 →
 * 监控 → 失效/回滚，与 SkillLifecycleService 同一套状态机纪律。
 *
 * <p><b>入库七条件（§11.2）在 publish 闸门逐条判定</b>（{@link #admissionChecks}）：
 * 条件 1/2/7 为结构类（VqrValidator 直接判定）；条件 3–6 为执行类证据——按 ADR-0017 D4
 * 由 catalog 之外流水线产生，本服务只校验**证据引用存在**（goldenRef / resultCheckRef），
 * 不连库执行、不重复校验证据内容（校验器不持有任何数据源连接）。
 * <b>点赞只加候选优先级，不触发发布</b>（§11.2）——本类没有从点赞直达 published 的路径。
 *
 * <p>自动失效（§11.3）经发布流水线生效（F-7）：清单升级时
 * {@link VqrInvalidationAnalyzer} 命中 → 下一版清单移除；catalog 侧运营跟进
 * {@link #retire}（运行时权威 = 清单）。回滚从不可变清单快照生成 patch+1 草稿。
 */
@Slf4j
@Service
public class VqrLifecycleService {

    private final JdbcVqrRepository repository;
    private final VqrValidator vqrValidator = new VqrValidator();

    public VqrLifecycleService(JdbcVqrRepository repository) {
        this.repository = repository;
    }

    /** 创建草稿：结构校验（不含绑定——草稿期资产引用可随后续发布补齐），字段回填到列。 */
    public VqrRecord createDraft(ManifestPayload.VqrMeta definition) {
        List<ManifestStructureValidator.Violation> violations =
                vqrValidator.validateStructure(definition);
        if (!violations.isEmpty()) {
            throw exception(SCENARIO_INVALID, "VQR 结构校验失败: " + violations);
        }
        VqrRecord record = new VqrRecord();
        record.setId(definition.getVqrId());
        record.setName(definition.getName());
        record.setSummary(definition.getSummary() == null ? definition.getName() : definition.getSummary());
        record.setStatus(CatalogAssetStatus.DRAFT.code());
        record.setOwner(definition.getOwner());
        record.setVersion(definition.getVersion());
        record.setQuestionPattern(definition.getQuestionPattern());
        record.setDslTemplate(definition.getDslTemplate());
        record.setParamsSchema(definition.getParamsSchema());
        record.setGoldenRef(definition.getGoldenRef());
        record.setResultCheckRef(definition.getResultCheckRef());
        record.setFreshnessLimitHours(definition.getFreshnessLimitHours());
        record.setInvalidationConditions(definition.getInvalidationConditions());
        record.setDefinition(definition);
        repository.insert(record);
        return record;
    }

    /** 提交审核：draft → reviewing（自动校验在 createDraft 已前置， reviewing 后等人工审核）。 */
    public VqrRecord submitForReview(String id) {
        VqrRecord record = require(id);
        transition(record, CatalogAssetStatus.REVIEWING);
        persist(record);
        return record;
    }

    /** 人工审核：reviewing 状态下记录审核人（留痕动作，不是状态迁移）。 */
    public VqrRecord approve(String id, String reviewer) {
        if (reviewer == null || reviewer.isBlank()) {
            throw exception(SCENARIO_INVALID, "审核人不能为空");
        }
        VqrRecord record = require(id);
        requireStatus(record, CatalogAssetStatus.REVIEWING);
        if (record.getDefinition() == null || !id.equals(record.getDefinition().getVqrId())) {
            throw exception(SCENARIO_INVALID, "记录 ID 与 definition.vqrId 不一致: " + id);
        }
        record.setReviewer(reviewer);
        persist(record);
        log.info("[approve][vqr({}) reviewer({})]", id, reviewer);
        return record;
    }

    /** 审核不通过：reviewing → draft。 */
    public VqrRecord reject(String id) {
        VqrRecord record = require(id);
        requireStatus(record, CatalogAssetStatus.REVIEWING);
        transition(record, CatalogAssetStatus.DRAFT);
        persist(record);
        return record;
    }

    /**
     * 发布（reviewing 且已有审核人）：入库七条件逐条判定通过才放行。
     * 失败消息带条件编号，便于运营定位缺哪条。
     */
    public VqrRecord publish(String id, ManifestPayload targetPayload) {
        VqrRecord record = require(id);
        requireStatus(record, CatalogAssetStatus.REVIEWING);
        if (record.getReviewer() == null || record.getReviewer().isBlank()) {
            throw exception(SCENARIO_REVIEW_REQUIRED, id);
        }
        if (record.getDefinition() == null || !id.equals(record.getDefinition().getVqrId())) {
            throw exception(SCENARIO_INVALID, "记录 ID 与 definition.vqrId 不一致: " + id);
        }
        List<String> failed = admissionChecks(record.getDefinition(), targetPayload);
        if (!failed.isEmpty()) {
            throw exception(SCENARIO_INVALID, "入库七条件未满足: " + failed);
        }
        transition(record, CatalogAssetStatus.PUBLISHED);
        record.setPublishedAt(Instant.now());
        persist(record);
        return record;
    }

    /** 下线（published → retired；published_at 保留供审计回放）。 */
    public VqrRecord retire(String id) {
        VqrRecord record = require(id);
        requireStatus(record, CatalogAssetStatus.PUBLISHED);
        transition(record, CatalogAssetStatus.RETIRED);
        persist(record);
        return record;
    }

    /** 回滚 = 从不可变清单快照生成补丁版本草稿（版本号必须 SemVer；patch+1）。 */
    public VqrRecord rollback(String id, ManifestPayload.VqrMeta snapshot, String operator) {
        VqrRecord current = require(id);
        CatalogAssetStatus currentStatus = CatalogAssetStatus.fromCode(current.getStatus());
        if (currentStatus != CatalogAssetStatus.PUBLISHED && currentStatus != CatalogAssetStatus.RETIRED) {
            throw exception(SCENARIO_STATE_INVALID, currentStatus.code(), "rollback");
        }
        if (snapshot == null || !id.equals(snapshot.getVqrId())) {
            throw exception(SCENARIO_INVALID, "回滚快照与记录不一致: " + id);
        }
        if (snapshot.getVersion() == null || !snapshot.getVersion().matches("\\d+\\.\\d+\\.\\d+")) {
            throw exception(SCENARIO_VERSION_INVALID, "回滚需要 SemVer 版本号: " + snapshot.getVersion());
        }
        String[] parts = snapshot.getVersion().split("\\.");
        String nextVersion = parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
        snapshot.setStatus(CatalogAssetStatus.DRAFT.code());
        snapshot.setVersion(nextVersion);
        snapshot.setOwner(operator == null || operator.isBlank() ? current.getOwner() : operator);
        VqrRecord record = new VqrRecord();
        record.setId(snapshot.getVqrId());
        record.setName(snapshot.getName());
        record.setSummary(snapshot.getSummary() == null ? snapshot.getName() : snapshot.getSummary());
        record.setStatus(CatalogAssetStatus.DRAFT.code());
        record.setOwner(snapshot.getOwner());
        record.setVersion(nextVersion);
        record.setQuestionPattern(snapshot.getQuestionPattern());
        record.setDslTemplate(snapshot.getDslTemplate());
        record.setParamsSchema(snapshot.getParamsSchema());
        record.setGoldenRef(snapshot.getGoldenRef());
        record.setResultCheckRef(snapshot.getResultCheckRef());
        record.setFreshnessLimitHours(snapshot.getFreshnessLimitHours());
        record.setInvalidationConditions(snapshot.getInvalidationConditions());
        record.setDefinition(snapshot);
        repository.insert(record);
        return record;
    }

    /**
     * 入库七条件（§11.2）逐条判定；返回**未满足**条件的描述（空 = 全部通过）。
     * 条件 3–6 按证据外置只验引用（D4）；点赞触发发布在此处天然被拒（无该路径）。
     */
    List<String> admissionChecks(ManifestPayload.VqrMeta definition, ManifestPayload targetPayload) {
        List<String> failed = new ArrayList<>();
        // 条件 1（DSL Schema 合法）+ 条件 2（依赖绑定稳定，按目标清单）+ 条件 7（owner/版本/新鲜度/失效条件齐全）
        vqrValidator.validateOne(definition, targetPayload).stream()
                .map(v -> "条件1/2/7: " + v)
                .forEach(failed::add);
        // 条件 3（Golden 校验通过）+ 4（目标库执行成功）+ 5（权限测试）+ 6（结果对题）：
        // D4 证据外置——catalog 只验引用存在与格式，证据内容由外部流水线产生并归档
        if (definition.getGoldenRef() == null || definition.getGoldenRef().isBlank()) {
            failed.add("条件3: 缺少 Golden 证据引用 (goldenRef)");
        }
        if (definition.getResultCheckRef() == null || definition.getResultCheckRef().isBlank()) {
            failed.add("条件4-6: 缺少结果校验证据引用 (resultCheckRef，外部流水线产物)");
        }
        if (definition.getFreshnessLimitHours() == null || definition.getFreshnessLimitHours() < 1) {
            failed.add("条件7: 数据新鲜度上限未声明 (freshnessLimitHours ≥ 1)");
        }
        if (definition.getInvalidationConditions() == null
                || definition.getInvalidationConditions().isEmpty()) {
            failed.add("条件7: 失效条件未声明 (invalidationConditions)");
        }
        return failed;
    }

    private void transition(VqrRecord record, CatalogAssetStatus target) {
        CatalogAssetStatus current = CatalogAssetStatus.fromCode(record.getStatus());
        if (!current.canTransitionTo(target)) {
            throw exception(SCENARIO_STATE_INVALID, current.code(), target.code());
        }
        record.setStatus(target.code());
    }

    private void requireStatus(VqrRecord record, CatalogAssetStatus expected) {
        CatalogAssetStatus current = CatalogAssetStatus.fromCode(record.getStatus());
        if (current != expected) {
            throw exception(SCENARIO_STATE_INVALID, current.code(), expected.code());
        }
    }

    private VqrRecord require(String id) {
        return repository.findById(id)
                .orElseThrow(() -> exception(SCENARIO_INVALID, "VQR 不存在: " + id));
    }

    private void persist(VqrRecord record) {
        if (repository.findById(record.getId()).isPresent()) {
            repository.update(record);
        } else {
            repository.insert(record);
        }
    }

}
