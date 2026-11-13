package com.ontos.module.catalog.service.asset;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.catalog.core.CatalogAssetStatus;
import com.ontos.module.catalog.core.SemanticAssetDefinition;
import com.ontos.module.catalog.core.SemanticAssetType;
import com.ontos.module.catalog.dal.dataobject.SemanticAssetRecord;
import com.ontos.module.catalog.dal.mysql.SemanticAssetRepository;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_NOT_FOUND;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_REVISION_CONFLICT;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_STATE_INVALID;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_VALIDATION_FAILED;

/**
 * 生效资产修订（B1/P1-B UI 补齐）：把生效清单中的资产回迁为草稿，供编辑器预填后重新走
 * 保存 → 校验 → 提交审核 → 合成发布。
 *
 * <p>两条铁律：
 * <ol>
 *     <li>生效清单是内容事实源——修订内容的 definition 一律取自 active payload，
 *     不信草稿表的滞留副本（清单回滚后两者可能不一致）；</li>
 *     <li>fail-closed——资产不在生效清单、或已有 draft/reviewing 待发布草稿时直接拒绝；
 *     published/retired 回迁时清空 published_at（V21 ck_sad_published_at 约束），
 *     并发修订以 ASSET_REVISION_CONFLICT 显式报错。</li>
 * </ol>
 *
 * <p>v22 基线等「清单有、草稿表无」的资产（基线加载不经草稿工作流）按清单定义播种草稿行。
 */
@Service
public class SemanticAssetRevisionService {

    private final SemanticAssetRepository repository;
    private final ManifestService manifestService;

    public SemanticAssetRevisionService(SemanticAssetRepository repository,
                                        ManifestService manifestService) {
        this.repository = repository;
        this.manifestService = manifestService;
    }

    /** 修订：返回回迁后的草稿视图（编辑器据此预填表单）。actor 为认证上下文当前发布者。 */
    public SemanticAssetView revise(String assetTypeWire, String id, String actor) {
        SemanticAssetType type = SemanticAssetType.of(assetTypeWire);
        String definitionJson = definitionFromActiveManifest(type, id);
        Map<String, Object> definition = SemanticAssetDefinition.parseObject(definitionJson);
        String bodyId = SemanticAssetDefinition.requireId(definition);
        if (!id.equals(bodyId)) {
            throw exception(ASSET_VALIDATION_FAILED, "生效清单定义 ID 与请求不一致: " + id);
        }
        String name = SemanticAssetDefinition.requireName(definition);
        String summary = SemanticAssetDefinition.summary(definition);

        Optional<SemanticAssetRecord> existing = repository.find(type.wire(), id);
        if (existing.isEmpty()) {
            insertSeedDraft(type, id, name, summary, definitionJson, actor);
        } else {
            reviseExisting(existing.get(), definitionJson, name, summary);
        }
        return SemanticAssetView.of(repository.find(type.wire(), id)
                .orElseThrow(() -> exception(ASSET_NOT_FOUND, id)));
    }

    /** published/retired → draft：条件更新防并发；0 行 = 期间被并发迁移，显式冲突。 */
    private void reviseExisting(SemanticAssetRecord current, String definitionJson,
                                String name, String summary) {
        if (CatalogAssetStatus.DRAFT.code().equals(current.getStatus())
                || CatalogAssetStatus.REVIEWING.code().equals(current.getStatus())) {
            throw exception(ASSET_STATE_INVALID, current.getStatus(),
                    "draft（已有待发布草稿，直接编辑即可）");
        }
        int updated = repository.reviseToDraft(current.getAssetType(), current.getId(),
                definitionJson, name, summary, current.getRevision());
        if (updated == 0) {
            throw exception(ASSET_REVISION_CONFLICT, current.getRevision(),
                    repository.findRevision(current.getAssetType(), current.getId()));
        }
    }

    /** 基线资产播种：owner=当前操作者、version=0.1.0 起步，revision=0，与 createDraft 初始态同构。 */
    private void insertSeedDraft(SemanticAssetType type, String id, String name,
                                 String summary, String definitionJson, String actor) {
        SemanticAssetRecord record = new SemanticAssetRecord();
        record.setAssetType(type.wire());
        record.setId(id);
        record.setName(name);
        record.setSummary(summary);
        record.setStatus(CatalogAssetStatus.DRAFT.code());
        record.setOwner(actor);
        record.setVersion("0.1.0");
        record.setRevision(0L);
        record.setDefinitionJson(definitionJson);
        repository.insert(record);
    }

    /** 从生效清单提取资产定义；清单缺失或资产不在清单时 fail-closed。 */
    private String definitionFromActiveManifest(SemanticAssetType type, String id) {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getPayloadJson() == null
                || manifest.getPayloadJson().isBlank()) {
            throw exception(ASSET_VALIDATION_FAILED, "没有生效清单，无法修订");
        }
        ManifestPayload payload = JsonUtils.parseObject(manifest.getPayloadJson(), ManifestPayload.class);
        if (payload == null) {
            throw exception(ASSET_VALIDATION_FAILED, "生效清单 payload 无法解析");
        }
        Object found = switch (type) {
            case CONCEPT -> findBy(payload.getOntology() == null ? null
                    : payload.getOntology().getConcepts(), id,
                    OntologySnapshot.Concept::getId);
            case PROPERTY -> findBy(payload.getOntology() == null ? null
                    : payload.getOntology().getProperties(), id,
                    OntologySnapshot.Property::getId);
            case RELATION -> findBy(payload.getOntology() == null ? null
                    : payload.getOntology().getRelations(), id,
                    OntologySnapshot.Relation::getId);
            case METRIC -> findBy(payload.getSemantic() == null ? null
                    : payload.getSemantic().getMetrics(), id,
                    SemanticManifest.Metric::getId);
            case DIMENSION -> findBy(payload.getSemantic() == null ? null
                    : payload.getSemantic().getDimensions(), id,
                    SemanticManifest.Dimension::getId);
        };
        if (found == null) {
            throw exception(ASSET_NOT_FOUND, id + "（不在生效清单中）");
        }
        return JsonUtils.toJsonString(found);
    }

    private static <T> T findBy(List<T> items, String id, Function<T, String> idOf) {
        if (items == null) {
            return null;
        }
        return items.stream()
                .filter(item -> id.equals(idOf.apply(item)))
                .findFirst()
                .orElse(null);
    }
}
