package com.ontos.module.query.controller.admin;

import java.util.List;
import java.util.Map;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.module.query.controller.admin.vo.QueryCompileRespVO;
import com.ontos.module.query.controller.admin.vo.QueryCompositeRespVO;
import com.ontos.module.query.controller.admin.vo.QueryDryRunRespVO;
import com.ontos.module.query.controller.admin.vo.QueryExecuteRespVO;
import com.ontos.module.query.controller.admin.vo.QueryPlanRespVO;
import com.ontos.module.query.controller.admin.vo.SkillDescriptorVO;
import com.ontos.module.query.core.skill.SkillDescriptor;
import com.ontos.module.query.core.skill.SkillRegistry;
import com.ontos.module.query.core.compiler.CompiledQuery;
import com.ontos.module.query.core.dsl.DslCodec;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.query.core.executor.DryRunOutcome;
import com.ontos.module.query.core.executor.QueryPlan;
import com.ontos.module.query.core.executor.QueryResult;
import com.ontos.module.query.service.CompositeOutcome;
import com.ontos.module.query.service.QueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import tools.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.framework.common.pojo.CommonResult.success;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;

/**
 * 确定性查询入口。请求体是类型化 DSL，不接受自然语言或自由 SQL。
 * 自然语言入口由 AG-UI agent 产出 DSL 后走同一 service。
 */
@Tag(name = "管理后台 - 确定性查询")
@RestController
@RequestMapping("/api/v1/query")
public class QueryController {

    private final QueryService queryService;
    private final DslCodec dslCodec;
    private final SkillRegistry skillRegistry;

    public QueryController(QueryService queryService, DslCodec dslCodec, SkillRegistry skillRegistry) {
        this.queryService = queryService;
        this.dslCodec = dslCodec;
        this.skillRegistry = skillRegistry;
    }

    @PostMapping("/compile")
    @Operation(summary = "编译 DSL（dry-plan，不执行）")
    public CommonResult<QueryCompileRespVO> compile(@RequestBody JsonNode body) {
        QueryDsl queryDsl = dslCodec.decode(body);
        CompiledQuery compiled = queryService.compile(queryDsl);
        QueryCompileRespVO vo = new QueryCompileRespVO();
        vo.setManifestId(compiled.getManifestId());
        vo.setContentHash(compiled.getContentHash());
        vo.setMetricId(compiled.getMetricId());
        vo.setMode(compiled.getMode());
        vo.setDimensions(compiled.getDimensions());
        vo.setJoins(compiled.getJoins());
        vo.setDialect(compiled.getDialect());
        vo.setSql(compiled.getSql());
        vo.setBindValues(compiled.getBindValues());
        vo.setAppliedLimit(compiled.getAppliedLimit());
        return success(vo);
    }

    @PostMapping("/execute")
    @Operation(summary = "编译并执行 DSL")
    public CommonResult<QueryExecuteRespVO> execute(@RequestBody JsonNode body) {
        QueryDsl queryDsl = dslCodec.decode(body);
        return success(toExecuteVO(queryService.execute(queryDsl)));
    }

    @GetMapping("/skills")
    @Operation(summary = "列出已注册能力与槽位契约（只读）")
    public CommonResult<List<SkillDescriptorVO>> skills() {
        return success(skillRegistry.list().stream().map(QueryController::toSkillVO).toList());
    }

    @PostMapping("/skills/{skillId}")
    @Operation(summary = "能力调用：槽位 → DSL → 正式执行通道（审计/状态机/幂等不变）")
    public CommonResult<QueryExecuteRespVO> executeSkill(@PathVariable String skillId,
                                                         @RequestBody(required = false) JsonNode body) {
        return success(toExecuteVO(queryService.executeSkill(skillId, parseSlots(body))));
    }

    /** 能力调用请求体契约固定为 {"slots": {...}}（缺省视为无槽位）；其他顶层字段一律拒绝（fail-closed）。 */
    private static Map<String, Object> parseSlots(JsonNode body) {
        if (body == null || body.isNull() || !body.isObject() || body.isEmpty()) {
            return Map.of();
        }
        if (body.size() != 1 || !body.has("slots") || !body.get("slots").isObject()) {
            throw exception(DSL_INVALID, "能力调用请求体必须是 {\"slots\": {...}}");
        }
        Object slots = com.ontos.framework.common.util.json.JsonUtils.parseMap(body.toString()).get("slots");
        if (!(slots instanceof Map<?, ?> slotMap)) {
            throw exception(DSL_INVALID, "slots 必须是对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) slotMap;
        return result;
    }

    private static QueryExecuteRespVO toExecuteVO(QueryResult result) {
        QueryExecuteRespVO vo = new QueryExecuteRespVO();
        vo.setManifestId(result.getManifestId());
        vo.setContentHash(result.getContentHash());
        vo.setColumns(result.getColumns());
        vo.setRows(result.getRows());
        vo.setRowCount(result.getRowCount());
        vo.setTruncated(result.isTruncated());
        vo.setElapsedMs(result.getElapsedMs());
        vo.setCached(result.isCached());
        vo.setLookupRows(toLookupRowVOs(result.getLookupRows()));
        return vo;
    }

    private static SkillDescriptorVO toSkillVO(SkillDescriptor descriptor) {
        SkillDescriptorVO vo = new SkillDescriptorVO();
        vo.setSkillId(descriptor.skillId());
        vo.setName(descriptor.name());
        vo.setSummary(descriptor.summary());
        vo.setSlots(descriptor.slots().stream().map(slot -> {
            SkillDescriptorVO.SlotVO slotVO = new SkillDescriptorVO.SlotVO();
            slotVO.setName(slot.name());
            slotVO.setRequired(slot.required());
            slotVO.setDescription(slot.description());
            return slotVO;
        }).toList());
        return vo;
    }

    @PostMapping("/dry-plan")
    @Operation(summary = "dry-plan：同一编译产物 + EXPLAIN 计划与成本（不执行业务查询）")
    public CommonResult<QueryPlanRespVO> dryPlan(@RequestBody JsonNode body) {
        return success(toPlanVO(queryService.dryPlan(dslCodec.decode(body))));
    }

    @PostMapping("/dry-run")
    @Operation(summary = "dry-run：同一编译产物受限执行（更紧的超时/行数，成本超限直接拒绝）")
    public CommonResult<QueryDryRunRespVO> dryRun(@RequestBody JsonNode body) {
        return success(toDryRunVO(queryService.dryRun(dslCodec.decode(body))));
    }

    @GetMapping("/plan/{requestId}")
    @Operation(summary = "按 request_id 回放最近一次编译产物（SQL / 绑定值 / 路径 / 计划）")
    public CommonResult<QueryPlanRespVO> replayPlan(@PathVariable String requestId) {
        return success(toPlanVO(queryService.replayPlan(requestId)));
    }

    @PostMapping("/composite/execute")
    @Operation(summary = "复合意图执行：无依赖子集逐子独立编译/授权/审计，失败整单拒绝")
    public CommonResult<QueryCompositeRespVO> executeComposite(@RequestBody JsonNode body) {
        return success(toCompositeVO(queryService.executeComposite(dslCodec.decode(body))));
    }

    private static QueryCompositeRespVO toCompositeVO(CompositeOutcome outcome) {
        QueryCompositeRespVO vo = new QueryCompositeRespVO();
        vo.setRequestId(outcome.requestId());
        vo.setTotalRowCount(outcome.totalRowCount());
        vo.setResults(outcome.results().stream().map(sub -> {
            QueryCompositeRespVO.SubResultVO subVO = new QueryCompositeRespVO.SubResultVO();
            subVO.setSubIntentId(sub.subIntentId());
            // T4.3 部分失败可解释：失败子带原因码、其余结果字段为空（不产出部分结果）
            if (sub.result() == null) {
                subVO.setErrorCode(sub.errorCode());
                subVO.setErrorMessage(sub.errorMessage());
                return subVO;
            }
            QueryResult result = sub.result();
            subVO.setManifestId(result.getManifestId());
            subVO.setContentHash(result.getContentHash());
            subVO.setColumns(result.getColumns());
            subVO.setRows(result.getRows());
            subVO.setRowCount(result.getRowCount());
            subVO.setTruncated(result.isTruncated());
            subVO.setElapsedMs(result.getElapsedMs());
            subVO.setLookupRows(toLookupRowVOs(result.getLookupRows()));
            return subVO;
        }).toList());
        return vo;
    }

    private static QueryPlanRespVO toPlanVO(QueryPlan plan) {
        QueryPlanRespVO vo = new QueryPlanRespVO();
        fillPlan(vo, plan);
        return vo;
    }

    private static QueryDryRunRespVO toDryRunVO(DryRunOutcome outcome) {
        QueryDryRunRespVO vo = new QueryDryRunRespVO();
        fillPlan(vo, outcome.plan());
        QueryResult result = outcome.result();
        vo.setColumns(result.getColumns());
        vo.setRows(result.getRows());
        vo.setRowCount(result.getRowCount());
        vo.setTruncated(result.isTruncated());
        vo.setElapsedMs(result.getElapsedMs());
        vo.setLookupRows(toLookupRowVOs(result.getLookupRows()));
        return vo;
    }

    private static void fillPlan(QueryPlanRespVO vo, QueryPlan plan) {
        CompiledQuery compiled = plan.getCompiled();
        vo.setManifestId(compiled.getManifestId());
        vo.setContentHash(compiled.getContentHash());
        vo.setMode(compiled.getMode());
        vo.setSql(compiled.getSql());
        vo.setBindValues(compiled.getBindValues());
        vo.setAppliedLimit(compiled.getAppliedLimit());
        vo.setJoins(compiled.getJoins());
        vo.setPlanJson(plan.getPlanJson());
        // 只编译没做过 EXPLAIN 的产物：cost / rows 在 QueryPlan 里是基本类型默认 0，
        // 直接透出会显示「未估算成本 · 成本 0」这种自相矛盾的组合，按「无值」返回。
        boolean explained = plan.getPlanJson() != null;
        vo.setEstimatedCost(explained ? plan.getEstimatedCost() : null);
        vo.setEstimatedRows(explained ? plan.getEstimatedRows() : null);
        vo.setStatus(plan.getStatus());
        vo.setBlockReason(plan.getBlockReason());
        vo.setVerified(plan.isVerified());
        vo.setCompiledAt(compiled.getCompiledAt());
    }

    private static List<QueryExecuteRespVO.LookupRowVO> toLookupRowVOs(List<QueryResult.LookupRow> rows) {
        if (rows == null) {
            return null;
        }
        return rows.stream().map(row -> {
            QueryExecuteRespVO.LookupRowVO lookupRow = new QueryExecuteRespVO.LookupRowVO();
            lookupRow.setIdentity(row.identity());
            lookupRow.setAvailable(row.available());
            lookupRow.setValues(row.values());
            return lookupRow;
        }).toList();
    }

}
