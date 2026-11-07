package com.ontos.module.ontology.service.agent;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.ontos.framework.common.agent.AgentContextProvider;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把当前生效发布清单里的指标与维度渲染成候选集，供 Agent 白名单引用。
 *
 * <p>为什么要在服务端注入：system prompt 要求「只能引用候选集中出现过的 ID」，但模型看不到清单。
 * PoC #3 实测中，缺少候选集会让模型退化成「能力发现」，或者自己去找文件（触发越权工具链），
 * 五类终态重建成功率仅 56%。
 *
 * <p>渲染结果按 `manifest_id + content_hash` 缓存，清单不变时不重复拼接。
 */
@Slf4j
@Component
public class ManifestCandidateContextProvider implements AgentContextProvider {

    private final ManifestService manifestService;

    private volatile String cachedKey;
    private volatile String cachedContext = "";

    public ManifestCandidateContextProvider(ManifestService manifestService) {
        this.manifestService = manifestService;
    }

    @Override
    public java.util.Set<String> candidateAssetIds() {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getSemantic() == null) {
            return java.util.Set.of();
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (manifest.getSemantic().getMetrics() != null) {
            manifest.getSemantic().getMetrics().forEach(metric -> ids.add(metric.getId()));
        }
        if (manifest.getSemantic().getDimensions() != null) {
            manifest.getSemantic().getDimensions().forEach(dimension -> ids.add(dimension.getId()));
        }
        return java.util.Set.copyOf(ids);
    }

    @Override
    public String candidateContext() {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getSemantic() == null) {
            return "";
        }
        String key = manifest.getManifestId() + "#" + manifest.getContentHash();
        if (Objects.equals(key, cachedKey)) {
            return cachedContext;
        }
        String rendered = render(manifest);
        cachedKey = key;
        cachedContext = rendered;
        return rendered;
    }

    private String render(PublishedManifest manifest) {
        SemanticManifest semantic = manifest.getSemantic();
        List<SemanticManifest.Metric> metrics = semantic.getMetrics() == null ? List.of() : semantic.getMetrics();
        List<SemanticManifest.Dimension> dimensions =
                semantic.getDimensions() == null ? List.of() : semantic.getDimensions();

        StringBuilder sb = new StringBuilder(1024);
        sb.append("## 当前候选集（白名单）\n\n");
        sb.append("manifest_id: ").append(manifest.getManifestId())
                .append(" · content_hash: ").append(shortHash(manifest.getContentHash()))
                .append(" · semantic_version: ").append(semantic.getSemanticVersion())
                .append("\n\n");
        sb.append("**只能使用下列 ID，未列出的 ID 一律视为不存在，不要编造。**\n\n");

        sb.append("### 指标（metric_id）\n");
        if (metrics.isEmpty()) {
            sb.append("- （当前清单没有可用指标）\n");
        } else {
            for (SemanticManifest.Metric metric : metrics) {
                sb.append("- ").append(metric.getId())
                        .append(" | ").append(metric.getName())
                        .append(" | 聚合=").append(metric.getAggregation())
                        .append(" | 可用维度=").append(join(metric.getAllowedDimensions()))
                        .append(" | 可用分析模式=").append(join(metric.getAllowedModes()))
                        .append('\n');
            }
        }

        sb.append("\n### 维度（dimension_id）\n");
        if (dimensions.isEmpty()) {
            sb.append("- （当前清单没有可用维度）\n");
        } else {
            for (SemanticManifest.Dimension dimension : dimensions) {
                sb.append("- ").append(dimension.getId())
                        .append(" | ").append(dimension.getName())
                        .append('\n');
            }
        }

        sb.append("\n### 使用约束\n");
        sb.append("- `dimensions` 与 `filters[].field_id` 只能取上面的 dimension_id；指标不能当维度用。\n");
        sb.append("- `metric_id` 只能取上面的 metric_id；`analysis_mode` 必须在对应指标的「可用分析模式」内。\n");
        sb.append("- 时间用 `time_expression_raw` 给相对表达（如「上个月」「近 7 天」），由服务端换算；不要输出绝对日期。\n");
        sb.append("- 用户问题涉及清单外能力时，输出 `out_of_capability=true` 并给原因码，不要给近似答案。\n");

        log.debug("[candidateContext] 渲染候选集 manifestId={} metrics={} dimensions={}",
                manifest.getManifestId(), metrics.size(), dimensions.size());
        return sb.toString();
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? "无" : values.stream().collect(Collectors.joining(", "));
    }

    private static String shortHash(String contentHash) {
        if (contentHash == null) {
            return "unknown";
        }
        return contentHash.length() <= 12 ? contentHash : contentHash.substring(0, 12) + "…";
    }

}
