package com.ontos.module.catalog.service.asset;

import java.util.List;
import java.util.Map;

import com.ontos.module.catalog.core.CatalogAssetStatus;
import com.ontos.module.catalog.core.SemanticAssetDefinition;
import com.ontos.module.catalog.core.SemanticAssetType;
import com.ontos.module.catalog.dal.dataobject.SemanticAssetRecord;
import com.ontos.module.catalog.dal.mysql.SemanticAssetRepository;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_ALREADY_EXISTS;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_DEFINITION_INVALID;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_ID_IMMUTABLE;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_NOT_FOUND;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_REVISION_CONFLICT;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_STATE_INVALID;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_VALIDATION_FAILED;

/**
 * 语义资产草稿生命周期（B1/W2）：P0 只放行 draft ⇄ reviewing。
 *
 * <p>review / publish 不在 P0：状态机实现上直接拒绝（{@link CatalogAssetStatus#canTransitionTo}），
 * 不预留空分支。发布仍走整包 {@code PublishServiceImpl}，草稿表不参与运行时读取。
 *
 * <p>并发：所有写入带 {@code expectedRevision}；影响行数为 0 时再查一次做精确归因
 * （不存在 / 状态不符 / 版本冲突），不让 SQL 合并语义。
 */
@Service
public class SemanticAssetLifecycleService {

    private final SemanticAssetRepository repository;
    private final SemanticAssetDraftValidator validator;

    public SemanticAssetLifecycleService(SemanticAssetRepository repository,
                                         SemanticAssetDraftValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    /** 新建草稿：owner 来自认证上下文（调用方传入），请求体不得携带。 */
    public SemanticAssetView createDraft(String assetTypeWire, String definitionJson, String actor) {
        SemanticAssetType type = SemanticAssetType.of(assetTypeWire);
        Map<String, Object> definition = SemanticAssetDefinition.parseObject(definitionJson);
        String id = SemanticAssetDefinition.requireId(definition);
        String name = SemanticAssetDefinition.requireName(definition);
        if (!type.matchesId(id)) {
            throw exception(ASSET_DEFINITION_INVALID,
                    "ID 前缀与类型不匹配: " + type.wire() + " / " + id);
        }
        if (repository.find(type.wire(), id).isPresent()) {
            throw exception(ASSET_ALREADY_EXISTS, id);
        }
        SemanticAssetRecord record = new SemanticAssetRecord();
        record.setAssetType(type.wire());
        record.setId(id);
        record.setName(name);
        record.setSummary(SemanticAssetDefinition.summary(definition));
        record.setStatus(CatalogAssetStatus.DRAFT.code());
        record.setOwner(actor);
        record.setVersion("0.1.0");
        record.setRevision(0L);
        record.setDefinitionJson(definitionJson);
        repository.insert(record);
        // 回读一次：updated_at 由 DB now() 生成，insert 后不回读会让响应出现 updatedAt=null
        return SemanticAssetView.of(repository.find(type.wire(), id).orElse(record));
    }

    public SemanticAssetView saveDraft(String assetTypeWire, String id, String definitionJson,
                                         long expectedRevision) {
        SemanticAssetType type = SemanticAssetType.of(assetTypeWire);
        SemanticAssetRecord current = require(type, id);
        requireStatus(current, CatalogAssetStatus.DRAFT);
        Map<String, Object> definition = SemanticAssetDefinition.parseObject(definitionJson);
        String bodyId = SemanticAssetDefinition.requireId(definition);
        if (!id.equals(bodyId)) {
            throw exception(ASSET_ID_IMMUTABLE, id + " / " + bodyId);
        }
        current.setName(SemanticAssetDefinition.requireName(definition));
        current.setSummary(SemanticAssetDefinition.summary(definition));
        current.setDefinitionJson(definitionJson);
        int updated = repository.updateDraft(current, expectedRevision);
        if (updated == 0) {
            throw conflictOrState(type, id, expectedRevision);
        }
        return SemanticAssetView.of(require(type, id));
    }

    /** 提交审核：先校验（合并视图），通过后 draft → reviewing。 */
    public SemanticAssetView submitReview(String assetTypeWire, String id, long expectedRevision) {
        SemanticAssetType type = SemanticAssetType.of(assetTypeWire);
        SemanticAssetRecord current = require(type, id);
        requireStatus(current, CatalogAssetStatus.DRAFT);
        SemanticAssetDraftValidator.ValidationResult result =
                validator.validate(type, current.getDefinitionJson());
        if (!result.valid()) {
            throw exception(ASSET_VALIDATION_FAILED, result.summary());
        }
        int updated = repository.updateStatus(type.wire(), id,
                CatalogAssetStatus.DRAFT.code(), CatalogAssetStatus.REVIEWING.code(), expectedRevision);
        if (updated == 0) {
            throw conflictOrState(type, id, expectedRevision);
        }
        return SemanticAssetView.of(require(type, id));
    }

    /** 撤回审核：reviewing → draft（审核中不允许直接改内容，必须先撤回）。 */
    public SemanticAssetView withdrawReview(String assetTypeWire, String id, long expectedRevision) {
        SemanticAssetType type = SemanticAssetType.of(assetTypeWire);
        SemanticAssetRecord current = require(type, id);
        requireStatus(current, CatalogAssetStatus.REVIEWING);
        int updated = repository.updateStatus(type.wire(), id,
                CatalogAssetStatus.REVIEWING.code(), CatalogAssetStatus.DRAFT.code(), expectedRevision);
        if (updated == 0) {
            throw conflictOrState(type, id, expectedRevision);
        }
        return SemanticAssetView.of(require(type, id));
    }

    public SemanticAssetView get(String assetTypeWire, String id) {
        return SemanticAssetView.of(require(SemanticAssetType.of(assetTypeWire), id));
    }

    public List<SemanticAssetView> list(String assetTypeWire, String status, String keyword,
                                          int page, int pageSize) {
        SemanticAssetType type = SemanticAssetType.of(assetTypeWire);
        // 上限防止 (page-1)*size 溢出成负 OFFSET（管理面不需要无限翻页）
        int safePage = Math.min(Math.max(page, 1), 10_000);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        return repository.findAll(type.wire(), status, keyword,
                        (safePage - 1) * safeSize, safeSize).stream()
                .map(SemanticAssetView::of)
                .toList();
    }

    public long count(String assetTypeWire, String status, String keyword) {
        SemanticAssetType type = SemanticAssetType.of(assetTypeWire);
        return repository.count(type.wire(), status, keyword);
    }

    private SemanticAssetRecord require(SemanticAssetType type, String id) {
        return repository.find(type.wire(), id)
                .orElseThrow(() -> exception(ASSET_NOT_FOUND, type.wire() + " / " + id));
    }

    private void requireStatus(SemanticAssetRecord record, CatalogAssetStatus expected) {
        if (!expected.code().equals(record.getStatus())) {
            throw exception(ASSET_STATE_INVALID, record.getStatus(), expected.code());
        }
    }

    private RuntimeException conflictOrState(SemanticAssetType type, String id, long expectedRevision) {
        SemanticAssetRecord current = repository.find(type.wire(), id)
                .orElseThrow(() -> exception(ASSET_NOT_FOUND, type.wire() + " / " + id));
        if (!CatalogAssetStatus.DRAFT.code().equals(current.getStatus())
                && !CatalogAssetStatus.REVIEWING.code().equals(current.getStatus())) {
            return exception(ASSET_STATE_INVALID, current.getStatus(), "draft|reviewing");
        }
        return exception(ASSET_REVISION_CONFLICT, expectedRevision, current.getRevision());
    }
}
