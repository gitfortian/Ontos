package com.ontos.module.ontology.controller.admin.manifest;

import java.util.List;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.module.ontology.controller.admin.manifest.vo.ManifestRespVO;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.framework.common.pojo.CommonResult.success;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.MANIFEST_NOT_FOUND;

@Tag(name = "管理后台 - 发布清单")
@RestController
@RequestMapping("/api/v1/ontology/manifests")
public class ManifestController {

    private final ManifestService manifestService;

    public ManifestController(ManifestService manifestService) {
        this.manifestService = manifestService;
    }

    @GetMapping("/current")
    @Operation(summary = "获取当前生效的发布清单")
    public CommonResult<ManifestRespVO> getCurrentManifest() {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null) {
            throw exception(MANIFEST_NOT_FOUND);
        }
        return success(buildRespVO(manifest));
    }

    @GetMapping("/{manifestId}")
    @Operation(summary = "按 ID 获取发布清单")
    @Parameter(name = "manifestId", description = "清单 ID", required = true)
    public CommonResult<ManifestRespVO> getManifest(@PathVariable("manifestId") String manifestId) {
        return success(manifestService.getManifest(manifestId)
                .map(ManifestController::buildRespVO)
                .orElseThrow(() -> exception(MANIFEST_NOT_FOUND)));
    }

    private static ManifestRespVO buildRespVO(PublishedManifest manifest) {
        ManifestRespVO vo = new ManifestRespVO();
        vo.setManifestId(manifest.getManifestId());
        vo.setContentHash(manifest.getContentHash());
        vo.setOntologyVersion(manifest.getOntologyVersion());
        vo.setSemanticVersion(manifest.getSemanticVersion());
        vo.setPolicyVersion(manifest.getPolicyVersion());
        vo.setRuleVersion(manifest.getRuleVersion());
        vo.setTimeParserVersion(manifest.getTimeParserVersion());
        vo.setCopyTemplateVersion(manifest.getCopyTemplateVersion());
        vo.setCreatedAt(manifest.getCreatedAt());
        vo.setMetricIds(manifest.getSemantic().getMetrics().stream()
                .map(metric -> metric.getId()).toList());
        vo.setConceptIds(manifest.getOntology().getConcepts().stream()
                .map(concept -> concept.getId()).toList());
        return vo;
    }

}
