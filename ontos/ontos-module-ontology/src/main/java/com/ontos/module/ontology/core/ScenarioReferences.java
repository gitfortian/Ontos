package com.ontos.module.ontology.core;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Scenario 引用索引（M6/W2/T2.2）：把资产引用规则收敛到一处，
 * 发布影响反查（ontology controller）与资产依赖分析（catalog）共用，避免两套口径漂移。
 */
public final class ScenarioReferences {

    private ScenarioReferences() {
    }

    /** 场景是否直接引用该资产。 */
    public static boolean references(ManifestPayload.ScenarioMeta scenario, String assetId) {
        if (scenario == null || assetId == null) {
            return false;
        }
        return assets(scenario).contains(assetId);
    }

    /** 场景直接引用的全部资产 ID（不含隐式依赖）。 */
    public static Set<String> assets(ManifestPayload.ScenarioMeta scenario) {
        Set<String> assets = new LinkedHashSet<>();
        if (scenario == null) {
            return assets;
        }
        ManifestPayload.OntologyScope ontology = scenario.getOntologyScope();
        if (ontology != null) {
            assets.addAll(ontology.getRootConcepts());
            assets.addAll(ontology.getAllowedRelations());
            assets.addAll(ontology.getExcludedConcepts());
        }
        ManifestPayload.SemanticScope semantic = scenario.getSemanticScope();
        if (semantic != null) {
            assets.addAll(semantic.getMetrics());
            assets.addAll(semantic.getDimensions());
            semantic.getFrozenCalibers().values().forEach(caliber -> {
                if (caliber != null && ("metric_id".equals(caliber.getType())
                        || "field_id".equals(caliber.getType()))) {
                    assets.add(caliber.getValue());
                }
            });
        }
        scenario.getSkills().forEach(skill -> assets.add(skill.getSkillId()));
        assets.addAll(scenario.getDefaultVqrIds());
        assets.addAll(scenario.getColdStartSamples());
        return assets;
    }
}
