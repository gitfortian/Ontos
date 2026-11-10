package com.ontos.module.catalog.service.scenario;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ontos.module.catalog.dal.dataobject.ScenarioRecord;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.ScenarioReferences;
import org.springframework.stereotype.Component;

/**
 * Scenario 依赖影响分析（M6/W2/T2.2，v1.4 §5.7 / §11.3 同型）：
 * 资产废弃/变更时反查受影响的已发布 Scenario；发布时校验引用是否仍在目标清单内。
 *
 * <p>Scenario 只直接引用资产（单向），不存在环；因此用集合反查即可，
 * 不引入图算法。若后续出现 Scenario 间依赖，再升级为图分析。
 */
@Component
public class ScenarioDependencyAnalyzer {

    /** 反查引用指定资产的已发布 Scenario。 */
    public List<String> impactedScenarios(String assetId, List<ScenarioRecord> records) {
        if (assetId == null || assetId.isBlank()) {
            return List.of();
        }
        return records.stream()
                .filter(ScenarioRecord::isPublished)
                .filter(record -> ScenarioReferences.references(record.getDefinition(), assetId))
                .map(ScenarioRecord::getId)
                .toList();
    }

    /** 批量资产（如一次发布删除/废弃多个资产）的并集影响面，结果去重保序。 */
    public List<String> impactedScenarios(Set<String> assetIds, List<ScenarioRecord> records) {
        Set<String> impacted = new LinkedHashSet<>();
        for (String assetId : assetIds) {
            impacted.addAll(impactedScenarios(assetId, records));
        }
        return List.copyOf(impacted);
    }

    /** Scenario 引用了目标清单中不存在的资产（发布即阻断，不静默降级）。 */
    public Set<String> missingReferences(ManifestPayload.ScenarioMeta scenario, ManifestPayload target) {
        if (scenario == null || target == null) {
            return Set.of();
        }
        Set<String> published = new LinkedHashSet<>();
        if (target.getOntology() != null) {
            target.getOntology().getConcepts().forEach(concept -> published.add(concept.getId()));
            target.getOntology().getRelations().forEach(relation -> published.add(relation.getId()));
            target.getOntology().getProperties().forEach(property -> published.add(property.getId()));
        }
        if (target.getSemantic() != null) {
            target.getSemantic().getMetrics().forEach(metric -> published.add(metric.getId()));
            target.getSemantic().getDimensions().forEach(dimension -> published.add(dimension.getId()));
        }
        Set<String> missing = new LinkedHashSet<>(ScenarioReferences.assets(scenario));
        missing.removeAll(published);
        // Skill / VQR / Golden 引用在 W3 扩展段落地前由 ScenarioValidator 按 PublishedRefs 判定；
        // 这里只负责本体与语义资产，避免把 skill.* 误报为不存在。
        missing.removeIf(asset -> asset.startsWith("skill.") || asset.startsWith("vqr."));
        return missing;
    }
}
