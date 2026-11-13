package com.ontos.module.agent.service.terminal;

import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 资产 ID → 业务名（对外展示统一出口）。
 *
 * <p>附录 A.4「ID 与文案分离」：澄清候选、默认建议、过程详情里凡是给用户看的资产，
 * 一律用清单里的业务名（{@code name}）；ID 只允许出现在事件 payload 的取值槽。
 * 查不到时回退中性文案（「未命名资产」），**绝不回退 ID**。
 */
@Component
public class AssetLabelResolver {

    /** 资产 ID 形态：段.段（metric.order_count / dim.region / concept.order）。 */
    private static final Pattern ASSET_ID = Pattern.compile("^[a-z][a-z0-9_]*\\.[a-z0-9_.]+$");

    private final ManifestService manifestService;

    public AssetLabelResolver(ManifestService manifestService) {
        this.manifestService = manifestService;
    }

    public String label(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            return "未指定";
        }
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getSemantic() == null) {
            return "未命名资产";
        }
        SemanticManifest semantic = manifest.getSemantic();
        String label = semantic.getMetrics().stream()
                .filter(metric -> assetId.equals(metric.getId()))
                .map(SemanticManifest.Metric::getName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(null);
        if (label == null) {
            label = semantic.getDimensions().stream()
                    .filter(dimension -> assetId.equals(dimension.getId()))
                    .map(SemanticManifest.Dimension::getName)
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        // 业务对象/信息项在本体段（澄清候选会以 concept/property 形态出现）
        if (label == null && manifest.getOntology() != null
                && manifest.getOntology().getConcepts() != null) {
            label = manifest.getOntology().getConcepts().stream()
                    .filter(concept -> assetId.equals(concept.getId()))
                    .map(concept -> concept.getName())
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        if (label == null && manifest.getOntology() != null
                && manifest.getOntology().getProperties() != null) {
            label = manifest.getOntology().getProperties().stream()
                    .filter(property -> assetId.equals(property.getId()))
                    .map(property -> property.getName())
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        return label == null ? "未命名资产" : label;
    }

    /** 值是否形如资产 ID（用于 defaultSuggestion 这类可能是文案也可能是 ID 的槽位兜底）。 */
    public boolean looksLikeAssetId(String value) {
        return value != null && ASSET_ID.matcher(value).matches();
    }
}
