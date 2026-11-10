package com.ontos.module.ontology.controller.admin.publish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.module.ontology.core.ManifestPayload;
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

    @GetMapping("/publish-records")
    @Operation(summary = "发布历史（含 dry-run 预检记录，最新在前）")
    public CommonResult<List<Map<String, Object>>> publishRecords(
            @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit) {
        return success(publishService.listPublishRecords(limit));
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
        // 业务化命名规格 §4：界面展示业务名（name），内部 ID 随对象返回仅供点选反查
        sections.put("metrics", manifest.getSemantic() == null ? List.of()
                : manifest.getSemantic().getMetrics().stream()
                        .map(m -> Map.of("id", m.getId(), "name", m.getName())).toList());
        sections.put("dimensions", manifest.getSemantic() == null ? List.of()
                : manifest.getSemantic().getDimensions().stream()
                        .map(d -> Map.of("id", d.getId(), "name", d.getName())).toList());
        sections.put("concepts", manifest.getOntology().getConcepts().stream()
                .map(c -> Map.of("id", c.getId(), "name", c.getName())).toList());
        sections.put("properties", manifest.getOntology().getProperties().stream()
                .map(p -> Map.of("id", p.getId(), "name", p.getName())).toList());
        sections.put("relations", manifest.getOntology().getRelations().stream()
                .map(r -> Map.of("id", r.getId(), "name", r.getName())).toList());
        sections.put("rules", manifest.getRules().stream()
                .map(r -> Map.of("id", r.getId(), "name", r.getName())).toList());
        sections.put("scenarios", manifest.getScenarios().stream()
                .map(s -> Map.of("id", s.getId(), "name", s.getName())).toList());
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
                .filter(scenario -> references(scenario, assetId))
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

    /** 场景是否引用该资产（引用口径统一在 ScenarioReferences）。 */
    private static boolean references(ManifestPayload.ScenarioMeta scenario, String assetId) {
        return com.ontos.module.ontology.core.ScenarioReferences.references(scenario, assetId);
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
