package com.ontos.module.catalog.service.asset;

import java.util.List;

import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import org.springframework.stereotype.Service;

/**
 * 编辑器候选元数据（B1/P1）：从当前生效清单投影，不读草稿表、不读 DAL。
 *
 * <p>为什么要单独一个接口：现有 `GET /ontology/assets` 按业务化命名规格只返回 `{id,name}`，
 * 前端无法据此做「对象 → 属性」联动；本接口只在管理面使用，返回 `conceptId/valueType` 等编辑必需字段，
 * 仍不暴露物理列。
 */
@Service
public class SemanticAssetOptionsService {

    private final ManifestService manifestService;

    public SemanticAssetOptionsService(ManifestService manifestService) {
        this.manifestService = manifestService;
    }

    public SemanticAssetOptionsView options() {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null) {
            return SemanticAssetOptionsView.empty();
        }
        SemanticManifest semantic = manifest.getSemantic();
        OntologySnapshot ontology = manifest.getOntology();
        return new SemanticAssetOptionsView(
                concepts(ontology),
                properties(ontology),
                dimensions(semantic),
                metrics(semantic),
                relations(ontology));
    }

    private static List<SemanticAssetOptionsView.AssetOption> concepts(OntologySnapshot ontology) {
        if (ontology == null || ontology.getConcepts() == null) {
            return List.of();
        }
        return ontology.getConcepts().stream()
                .map(concept -> new SemanticAssetOptionsView.AssetOption(
                        concept.getId(), concept.getName()))
                .toList();
    }

    private static List<SemanticAssetOptionsView.PropertyOption> properties(OntologySnapshot ontology) {
        if (ontology == null || ontology.getProperties() == null) {
            return List.of();
        }
        return ontology.getProperties().stream()
                .map(property -> new SemanticAssetOptionsView.PropertyOption(
                        property.getId(), property.getName(), property.getConceptId(),
                        property.getValueType()))
                .toList();
    }

    private static List<SemanticAssetOptionsView.AssetOption> dimensions(SemanticManifest semantic) {
        if (semantic == null || semantic.getDimensions() == null) {
            return List.of();
        }
        return semantic.getDimensions().stream()
                .map(dimension -> new SemanticAssetOptionsView.AssetOption(
                        dimension.getId(), dimension.getName()))
                .toList();
    }

    private static List<SemanticAssetOptionsView.AssetOption> metrics(SemanticManifest semantic) {
        if (semantic == null || semantic.getMetrics() == null) {
            return List.of();
        }
        return semantic.getMetrics().stream()
                .map(metric -> new SemanticAssetOptionsView.AssetOption(
                        metric.getId(), metric.getName()))
                .toList();
    }

    private static List<SemanticAssetOptionsView.AssetOption> relations(OntologySnapshot ontology) {
        if (ontology == null || ontology.getRelations() == null) {
            return List.of();
        }
        return ontology.getRelations().stream()
                .map(relation -> new SemanticAssetOptionsView.AssetOption(
                        relation.getId(), relation.getName()))
                .toList();
    }
}
