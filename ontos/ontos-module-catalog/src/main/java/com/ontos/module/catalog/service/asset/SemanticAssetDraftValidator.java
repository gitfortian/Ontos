package com.ontos.module.catalog.service.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.catalog.core.SemanticAssetDefinition;
import com.ontos.module.catalog.core.SemanticAssetType;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.ManifestStructureValidator;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_DEFINITION_INVALID;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_ID_IMMUTABLE;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_VALIDATION_FAILED;

/**
 * 语义资产草稿校验（B1/W3）：在「生效清单 + 草稿覆盖」的合并视图上跑发布侧同一套结构校验。
 *
 * <p>为什么不能只校验草稿本身：{@code metric.propertyId} 需要属性存在、{@code property.conceptId}
 * 需要对象存在、场景 scope 需要指标存在——脱离清单上下文，引用完整性必然误报。
 *
 * <p>返回结果只保留本资产的 violations：校验器 location 是「类型名 + '.' + 资产 ID」
 * （如 {@code metric.metric.gmv}），按前缀精确匹配，不按数组索引猜。
 *
 * <p><b>不缓存基线</b>（对 B1 规格 §3.3.2 第 5 步的实施修正）：Node 侧校验一次只解析一次
 * active payload，管理面低频；要缓存就必须深拷贝 payload 才能防止合并污染，成本与重新解析相同，
 * 收益为零。若后续实测成为瓶颈，再加 copy-on-write 缓存并补基准。
 */
@Service
public class SemanticAssetDraftValidator {

    private final ManifestService manifestService;
    private final ManifestStructureValidator structureValidator = new ManifestStructureValidator();

    public SemanticAssetDraftValidator(ManifestService manifestService) {
        this.manifestService = manifestService;
    }

    /** 校验结果：valid 为 true 时 violations 必为空。 */
    public record ValidationResult(boolean valid, List<ManifestStructureValidator.Violation> violations) {

        /** 供错误消息使用的紧凑摘要（最多 5 条，避免业务码消息过长）。 */
        public String summary() {
            int limit = Math.min(violations.size(), 5);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                if (i > 0) {
                    text.append("; ");
                }
                text.append(violations.get(i));
            }
            if (violations.size() > limit) {
                text.append("; ...共 ").append(violations.size()).append(" 条");
            }
            return text.toString();
        }
    }

    public ValidationResult validate(SemanticAssetType type, String definitionJson) {
        return validate(type, null, definitionJson);
    }

    /**
     * 带期望 ID 的校验：路径 ID 与 definition.id 必须一致。
     *
     * <p>否则「校验 /A」可能实际校验了 /B 并返回 B 的 violations，调用方会误以为 A 有问题。
     * {@code expectedId} 为 null 时不比对（内部调用）。
     */
    public ValidationResult validate(SemanticAssetType type, String expectedId, String definitionJson) {
        String id = SemanticAssetDefinition.requireId(SemanticAssetDefinition.parseObject(definitionJson));
        if (expectedId != null && !expectedId.equals(id)) {
            throw exception(ASSET_ID_IMMUTABLE, expectedId + " / " + id);
        }
        if (!type.matchesId(id)) {
            throw exception(ASSET_DEFINITION_INVALID,
                    "ID 前缀与类型不匹配: " + type.wire() + " / " + id);
        }
        ManifestPayload merged = merge(activePayload(), type, id, definitionJson);
        List<ManifestStructureValidator.Violation> all = structureValidator.validate(merged);
        String prefix = type.locationPrefix(id);
        List<ManifestStructureValidator.Violation> mine = all.stream()
                .filter(violation -> violation.location() != null
                        && violation.location().startsWith(prefix))
                .toList();
        return new ValidationResult(mine.isEmpty(), mine);
    }

    private ManifestPayload activePayload() {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getPayloadJson() == null
                || manifest.getPayloadJson().isBlank()) {
            throw exception(ASSET_VALIDATION_FAILED, "没有生效清单，无法校验草稿");
        }
        ManifestPayload payload = JsonUtils.parseObject(manifest.getPayloadJson(), ManifestPayload.class);
        if (payload == null) {
            throw exception(ASSET_VALIDATION_FAILED, "生效清单 payload 无法解析");
        }
        return payload;
    }

    /** 深拷贝基线后覆盖草稿：避免修改 ManifestService 持有的/缓存的清单对象。 */
    private ManifestPayload merge(ManifestPayload active, SemanticAssetType type, String id,
                                  String definitionJson) {
        ManifestPayload merged = JsonUtils.parseObject(
                JsonUtils.toJsonString(active), ManifestPayload.class);
        if (merged == null) {
            throw exception(ASSET_VALIDATION_FAILED, "生效清单 payload 无法复制");
        }
        if (merged.getOntology() == null) {
            merged.setOntology(new OntologySnapshot());
        }
        if (merged.getSemantic() == null) {
            merged.setSemantic(new SemanticManifest());
        }
        try {
            switch (type) {
                case CONCEPT -> merged.getOntology().setConcepts(upsert(
                        merged.getOntology().getConcepts(),
                        JsonUtils.parseObject(definitionJson, OntologySnapshot.Concept.class),
                        OntologySnapshot.Concept::getId));
                case PROPERTY -> merged.getOntology().setProperties(upsert(
                        merged.getOntology().getProperties(),
                        JsonUtils.parseObject(definitionJson, OntologySnapshot.Property.class),
                        OntologySnapshot.Property::getId));
                case RELATION -> merged.getOntology().setRelations(upsert(
                        merged.getOntology().getRelations(),
                        JsonUtils.parseObject(definitionJson, OntologySnapshot.Relation.class),
                        OntologySnapshot.Relation::getId));
                case METRIC -> merged.getSemantic().setMetrics(upsert(
                        merged.getSemantic().getMetrics(),
                        JsonUtils.parseObject(definitionJson, SemanticManifest.Metric.class),
                        SemanticManifest.Metric::getId));
                case DIMENSION -> merged.getSemantic().setDimensions(upsert(
                        merged.getSemantic().getDimensions(),
                        JsonUtils.parseObject(definitionJson, SemanticManifest.Dimension.class),
                        SemanticManifest.Dimension::getId));
            }
        } catch (RuntimeException ex) {
            throw exception(ASSET_DEFINITION_INVALID, "definition 结构无法解析: " + ex.getMessage());
        }
        return merged;
    }

    private static <T> List<T> upsert(List<T> existing, T draft, Function<T, String> idOf) {
        List<T> list = new ArrayList<>(existing == null ? List.of() : existing);
        String draftId = idOf.apply(draft);
        for (int i = 0; i < list.size(); i++) {
            if (draftId != null && draftId.equals(idOf.apply(list.get(i)))) {
                list.set(i, draft);
                return list;
            }
        }
        list.add(draft);
        return list;
    }
}
