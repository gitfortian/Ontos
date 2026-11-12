package com.ontos.module.catalog.controller.admin.asset;

import java.util.List;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.framework.common.pojo.PageResult;
import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.catalog.controller.admin.asset.vo.SemanticAssetCreateReqVO;
import com.ontos.module.catalog.controller.admin.asset.vo.SemanticAssetRespVO;
import com.ontos.module.catalog.controller.admin.asset.vo.SemanticAssetRevisionReqVO;
import com.ontos.module.catalog.controller.admin.asset.vo.SemanticAssetSaveReqVO;
import com.ontos.module.catalog.controller.admin.asset.vo.SemanticAssetSummaryVO;
import com.ontos.module.catalog.controller.admin.asset.vo.SemanticAssetValidateReqVO;
import com.ontos.module.catalog.controller.admin.asset.vo.SemanticAssetValidationRespVO;
import com.ontos.module.catalog.core.SemanticAssetType;
import com.ontos.module.catalog.service.asset.SemanticAssetView;
import com.ontos.module.catalog.service.asset.SemanticAssetDraftValidator;
import com.ontos.module.catalog.service.asset.SemanticAssetLifecycleService;
import com.ontos.module.ontology.service.publish.PublishGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.ontos.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.framework.common.pojo.CommonResult.success;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_DEFINITION_INVALID;

/**
 * 管理后台 - 语义资产草稿（B1/W4）。
 *
 * <p>鉴权走 {@link PublishGuard} 管理面闸门：dev profile 放行，其余 403。这是 P0 的已知限制，
 * 触发点 = ADR-0021 角色模型落地；不得在 controller 里自行判断角色。
 *
 * <p>身份只来自认证上下文：{@code owner} 取 {@link PublishGuard#currentPublisher()}，
 * 请求体不接收 owner / reviewer / status。
 */
@Tag(name = "管理后台 - 语义资产草稿")
@RestController
@RequestMapping("/api/v1/catalog/assets")
public class SemanticAssetController {

    private final SemanticAssetLifecycleService lifecycleService;
    private final SemanticAssetDraftValidator draftValidator;
    private final PublishGuard publishGuard;

    public SemanticAssetController(SemanticAssetLifecycleService lifecycleService,
                                   SemanticAssetDraftValidator draftValidator,
                                   PublishGuard publishGuard) {
        this.lifecycleService = lifecycleService;
        this.draftValidator = draftValidator;
        this.publishGuard = publishGuard;
    }

    @PostMapping("/{assetType}/drafts")
    @Operation(summary = "创建语义资产草稿")
    @Parameter(name = "assetType", description = "concept / property / relation / metric / dimension", required = true)
    public CommonResult<SemanticAssetRespVO> create(
            @PathVariable("assetType") String assetType,
            @RequestBody SemanticAssetCreateReqVO request) {
        publishGuard.requirePublishAllowed("catalog.asset.draft.create");
        rejectUnknown(request.getUnknownFields());
        SemanticAssetView view = lifecycleService.createDraft(
                assetType, json(request.getDefinition()), publishGuard.currentPublisher());
        return success(toResp(view));
    }

    @PutMapping("/{assetType}/{id}/draft")
    @Operation(summary = "保存语义资产草稿（乐观锁）")
    public CommonResult<SemanticAssetRespVO> save(
            @PathVariable("assetType") String assetType,
            @PathVariable("id") String id,
            @RequestBody SemanticAssetSaveReqVO request) {
        publishGuard.requirePublishAllowed("catalog.asset.draft.save");
        rejectUnknown(request.getUnknownFields());
        SemanticAssetView view = lifecycleService.saveDraft(
                assetType, id, json(request.getDefinition()),
                requireRevision(request.getExpectedRevision()));
        return success(toResp(view));
    }

    @GetMapping("/{assetType}/{id}")
    @Operation(summary = "获取语义资产草稿详情")
    public CommonResult<SemanticAssetRespVO> get(
            @PathVariable("assetType") String assetType,
            @PathVariable("id") String id) {
        publishGuard.requirePublishAllowed("catalog.asset.draft.get");
        return success(toResp(lifecycleService.get(assetType, id)));
    }

    @GetMapping("/{assetType}")
    @Operation(summary = "语义资产草稿列表（不含 definition）")
    public CommonResult<PageResult<SemanticAssetSummaryVO>> list(
            @PathVariable("assetType") String assetType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "pageSize", required = false, defaultValue = "20") Integer pageSize) {
        publishGuard.requirePublishAllowed("catalog.asset.draft.list");
        List<SemanticAssetSummaryVO> list = lifecycleService
                .list(assetType, status, keyword, page, pageSize).stream()
                .map(SemanticAssetController::toSummary)
                .toList();
        long total = lifecycleService.count(assetType, status, keyword);
        return success(new PageResult<>(list, total));
    }

    @PostMapping("/{assetType}/{id}/validate")
    @Operation(summary = "校验语义资产草稿（合并生效清单；不落库）")
    public CommonResult<SemanticAssetValidationRespVO> validate(
            @PathVariable("assetType") String assetType,
            @PathVariable("id") String id,
            @RequestBody(required = false) SemanticAssetValidateReqVO request) {
        publishGuard.requirePublishAllowed("catalog.asset.draft.validate");
        if (request != null) {
            rejectUnknown(request.getUnknownFields());
        }
        SemanticAssetType type = SemanticAssetType.of(assetType);
        String definitionJson = request != null && request.getDefinition() != null
                ? json(request.getDefinition())
                : lifecycleService.get(assetType, id).definitionJson();
        SemanticAssetDraftValidator.ValidationResult result =
                draftValidator.validate(type, id, definitionJson);
        List<SemanticAssetValidationRespVO.ViolationVO> violations = result.violations().stream()
                .map(violation -> new SemanticAssetValidationRespVO.ViolationVO(
                        violation.location(), violation.message()))
                .toList();
        return success(new SemanticAssetValidationRespVO(result.valid(), violations));
    }

    @PostMapping("/{assetType}/{id}/submit-review")
    @Operation(summary = "提交语义资产草稿审核")
    public CommonResult<SemanticAssetRespVO> submitReview(
            @PathVariable("assetType") String assetType,
            @PathVariable("id") String id,
            @RequestBody SemanticAssetRevisionReqVO request) {
        publishGuard.requirePublishAllowed("catalog.asset.draft.submit");
        rejectUnknown(request.getUnknownFields());
        SemanticAssetView view = lifecycleService.submitReview(
                assetType, id, requireRevision(request.getExpectedRevision()));
        return success(toResp(view));
    }

    @PostMapping("/{assetType}/{id}/withdraw-review")
    @Operation(summary = "撤回语义资产草稿审核")
    public CommonResult<SemanticAssetRespVO> withdrawReview(
            @PathVariable("assetType") String assetType,
            @PathVariable("id") String id,
            @RequestBody SemanticAssetRevisionReqVO request) {
        publishGuard.requirePublishAllowed("catalog.asset.draft.withdraw");
        rejectUnknown(request.getUnknownFields());
        SemanticAssetView view = lifecycleService.withdrawReview(
                assetType, id, requireRevision(request.getExpectedRevision()));
        return success(toResp(view));
    }

    /** 请求体不得携带 owner / reviewer / status 等服务端状态字段：收集到即拒绝，不静默忽略。 */
    private static void rejectUnknown(java.util.Map<String, Object> unknownFields) {
        if (unknownFields != null && !unknownFields.isEmpty()) {
            throw exception(ASSET_DEFINITION_INVALID,
                    "请求体不接受字段: " + unknownFields.keySet());
        }
    }

    private static String json(Object definition) {
        if (definition == null) {
            throw exception(ASSET_DEFINITION_INVALID, "definition 必填");
        }
        return JsonUtils.toJsonString(definition);
    }

    private static long requireRevision(Long expectedRevision) {
        if (expectedRevision == null || expectedRevision < 0) {
            throw exception(BAD_REQUEST);
        }
        return expectedRevision;
    }

    private static SemanticAssetRespVO toResp(SemanticAssetView view) {
        SemanticAssetRespVO vo = new SemanticAssetRespVO();
        vo.setAssetType(view.assetType());
        vo.setId(view.id());
        vo.setName(view.name());
        vo.setSummary(view.summary());
        vo.setStatus(view.status());
        vo.setOwner(view.owner());
        vo.setReviewer(view.reviewer());
        vo.setVersion(view.version());
        vo.setRevision(view.revision());
        vo.setDefinition(JsonUtils.parseMap(view.definitionJson()));
        vo.setPublishedAt(view.publishedAt());
        vo.setUpdatedAt(view.updatedAt());
        return vo;
    }

    private static SemanticAssetSummaryVO toSummary(SemanticAssetView view) {
        SemanticAssetSummaryVO vo = new SemanticAssetSummaryVO();
        vo.setAssetType(view.assetType());
        vo.setId(view.id());
        vo.setName(view.name());
        vo.setSummary(view.summary());
        vo.setStatus(view.status());
        vo.setOwner(view.owner());
        vo.setVersion(view.version());
        vo.setRevision(view.revision());
        vo.setUpdatedAt(view.updatedAt());
        return vo;
    }
}
