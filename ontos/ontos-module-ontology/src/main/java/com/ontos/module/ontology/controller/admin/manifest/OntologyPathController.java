package com.ontos.module.ontology.controller.admin.manifest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.module.ontology.core.OntologyPathPlanner;
import com.ontos.module.ontology.core.OntologyPathPlanner.PlanResult;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.framework.common.pojo.CommonResult.success;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.MANIFEST_NOT_FOUND;

/**
 * 本体路径规划调试端点（M1/T4.3）。
 *
 * <p>domains 参数即授权子图（T6.2 的最小实现口径）：未传 = 全域调试态；
 * 真实「身份 → 域」映射由 M4 网关策略接管后，此处改从认证上下文读取。
 */
@Tag(name = "管理后台 - 本体路径规划")
@RestController
@RequestMapping("/api/v1/ontology/paths")
public class OntologyPathController {

    private final ManifestService manifestService;

    public OntologyPathController(ManifestService manifestService) {
        this.manifestService = manifestService;
    }

    @GetMapping
    @Operation(summary = "规划两个业务对象间的合法关系路径（并列返回歧义，不私自选择）")
    public CommonResult<PlanResult> paths(
            @RequestParam("from") @Parameter(description = "起点业务对象 ID") String from,
            @RequestParam("to") @Parameter(description = "终点业务对象 ID") String to,
            @RequestParam(value = "domains", required = false) @Parameter(description = "授权域（逗号分隔，缺省=全域调试）")
            String domains,
            @RequestParam(value = "maxHops", required = false) @Parameter(description = "最大跳数，缺省 3")
            Integer maxHops) {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getOntology() == null) {
            throw exception(MANIFEST_NOT_FOUND);
        }
        Set<String> authorizedDomains = domains == null || domains.isBlank()
                ? Set.of()
                : Set.of(domains.split(","));
        OntologyPathPlanner planner = new OntologyPathPlanner(manifest.getOntology());
        PlanResult result = planner.plan(from, to, authorizedDomains,
                maxHops == null ? OntologyPathPlanner.DEFAULT_MAX_HOPS : maxHops);
        return success(result);
    }

    @GetMapping("/candidates")
    @Operation(summary = "全部可查询关系的规划词表（探针与前端调试用）")
    public CommonResult<Map<String, Object>> candidates() {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getOntology() == null) {
            throw exception(MANIFEST_NOT_FOUND);
        }
        OntologyPathPlanner planner = new OntologyPathPlanner(manifest.getOntology());
        List<PlanResult> all = manifest.getOntology().getRelations().stream()
                .map(relation -> planner.plan(relation.getFrom(), relation.getTo(),
                        Set.of(), OntologyPathPlanner.DEFAULT_MAX_HOPS))
                .toList();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("costFunctionVersion", OntologyPathPlanner.COST_FUNCTION_VERSION);
        result.put("maxHops", OntologyPathPlanner.DEFAULT_MAX_HOPS);
        result.put("directRelations", manifest.getOntology().getRelations().stream()
                .filter(relation -> relation.isQueryable())
                .map(relation -> java.util.Map.of(
                        "relationId", relation.getId(),
                        "from", relation.getFrom(),
                        "to", relation.getTo(),
                        "cardinality", relation.getCardinality(),
                        "goldenPath", relation.isGoldenPath()))
                .toList());
        result.put("directPlans", all);
        return success(result);
    }

}
