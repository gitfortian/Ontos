package com.ontos.module.ontology.controller.admin.publish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.ontology.service.publish.PublishService;
import com.ontos.module.ontology.service.publish.PublishService.PublishRequest;
import com.ontos.module.ontology.service.publish.PublishService.PublishReportVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.ontos.framework.common.pojo.CommonResult.success;

/**
 * 发布链路管理端点（M1/T3.1）。策略闸门保持 fail-closed：
 * M4 策略快照落地前，非 local profile 下随全局策略整体 403，生产发布由网关授权的运维身份执行。
 */
@Tag(name = "管理后台 - 发布链路")
@RestController
@RequestMapping("/api/v1/ontology")
public class OntologyPublishController {

    private final PublishService publishService;
    private final ManifestService manifestService;

    public OntologyPublishController(PublishService publishService, ManifestService manifestService) {
        this.publishService = publishService;
        this.manifestService = manifestService;
    }

    @PostMapping("/publish")
    @Operation(summary = "发布清单（dry-run 预检 / 确认发布）")
    public CommonResult<PublishReportVO> publish(@RequestBody PublishRequest request) {
        return success(publishService.publish(request));
    }

    @GetMapping("/assets")
    @Operation(summary = "浏览已发布资产（按类型分组，默认当前生效清单）")
    public CommonResult<Map<String, Object>> assets(
            @RequestParam(value = "manifestId", required = false) String manifestId) {
        PublishedManifest manifest = manifestId == null
                ? manifestService.getActiveManifestOrNull()
                : manifestService.getManifest(manifestId).orElse(null);
        if (manifest == null) {
            return success(Map.of("empty", true));
        }
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("manifestId", manifest.getManifestId());
        sections.put("contentHash", manifest.getContentHash());
        sections.put("metrics", manifest.getSemantic() == null ? List.of()
                : manifest.getSemantic().getMetrics().stream().map(m -> m.getId()).toList());
        sections.put("dimensions", manifest.getSemantic() == null ? List.of()
                : manifest.getSemantic().getDimensions().stream().map(d -> d.getId()).toList());
        sections.put("concepts", manifest.getOntology().getConcepts().stream().map(c -> c.getId()).toList());
        sections.put("properties", manifest.getOntology().getProperties().stream().map(p -> p.getId()).toList());
        sections.put("relations", manifest.getOntology().getRelations().stream().map(r -> r.getId()).toList());
        sections.put("rules", manifest.getRules().stream().map(r -> r.getId()).toList());
        sections.put("scenarios", manifest.getScenarios().stream().map(s -> s.getId()).toList());
        return success(sections);
    }

    @GetMapping("/manifests/{manifestId}/diff/{baseId}")
    @Operation(summary = "两个清单的资产级 diff（新增 / 移除）")
    public CommonResult<Map<String, Object>> diff(@PathVariable("manifestId") String manifestId,
                                                  @PathVariable("baseId") String baseId) {
        PublishedManifest current = manifestService.getManifest(manifestId).orElse(null);
        PublishedManifest base = manifestService.getManifest(baseId).orElse(null);
        if (current == null || base == null) {
            return success(Map.of("empty", true, "reason", "清单不存在"));
        }
        Map<String, Set<String>> currentIds = assetIdSections(current);
        Map<String, Set<String>> baseIds = assetIdSections(base);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("manifestId", manifestId);
        diff.put("baseId", baseId);
        for (String section : currentIds.keySet()) {
            Set<String> added = new java.util.HashSet<>(currentIds.get(section));
            added.removeAll(baseIds.getOrDefault(section, Set.of()));
            Set<String> removed = new java.util.HashSet<>(baseIds.getOrDefault(section, Set.of()));
            removed.removeAll(currentIds.get(section));
            if (!added.isEmpty() || !removed.isEmpty()) {
                diff.put(section, Map.of("added", added, "removed", removed));
            }
        }
        return success(diff);
    }

    @GetMapping("/impact")
    @Operation(summary = "资产影响反查（被哪些规则 / 场景 / 指标引用）")
    public CommonResult<Map<String, Object>> impact(@RequestParam("assetId") String assetId) {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assetId", assetId);
        if (manifest == null) {
            result.put("empty", true);
            return success(result);
        }
        List<String> rules = manifest.getRules().stream()
                .filter(rule -> assetId.equals(rule.getExpression().get("metricId")))
                .map(rule -> rule.getId())
                .toList();
        List<String> scenarios = manifest.getScenarios().stream()
                .filter(scenario -> scenario.getSemanticScope().contains(assetId)
                        || scenario.getOntologyScope().contains(assetId))
                .map(scenario -> scenario.getId())
                .toList();
        List<String> metrics = new ArrayList<>();
        if (manifest.getSemantic() != null) {
            manifest.getSemantic().getMetrics().stream()
                    .filter(metric -> metric.getAllowedDimensions().contains(assetId))
                    .forEach(metric -> metrics.add(metric.getId()));
        }
        result.put("rules", rules);
        result.put("scenarios", scenarios);
        result.put("metricsAllowedDimensions", metrics);
        return success(result);
    }

    private static Map<String, Set<String>> assetIdSections(PublishedManifest manifest) {
        Map<String, Set<String>> sections = new LinkedHashMap<>();
        if (manifest.getSemantic() != null) {
            sections.put("metrics", idsToSet(manifest.getSemantic().getMetrics(), m -> m.getId()));
            sections.put("dimensions", idsToSet(manifest.getSemantic().getDimensions(), d -> d.getId()));
        }
        sections.put("concepts", idsToSet(manifest.getOntology().getConcepts(), c -> c.getId()));
        sections.put("relations", idsToSet(manifest.getOntology().getRelations(), r -> r.getId()));
        sections.put("rules", idsToSet(manifest.getRules(), r -> r.getId()));
        sections.put("scenarios", idsToSet(manifest.getScenarios(), s -> s.getId()));
        return sections;
    }

    private static <T> Set<String> idsToSet(List<T> list, java.util.function.Function<T, String> idOf) {
        Set<String> ids = new java.util.HashSet<>();
        if (list != null) {
            list.forEach(item -> ids.add(idOf.apply(item)));
        }
        return ids;
    }

}
