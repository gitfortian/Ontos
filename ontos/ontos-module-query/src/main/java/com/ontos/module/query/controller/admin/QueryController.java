package com.ontos.module.query.controller.admin;

import java.util.List;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.module.query.controller.admin.vo.QueryCompileRespVO;
import com.ontos.module.query.controller.admin.vo.QueryCompositeRespVO;
import com.ontos.module.query.controller.admin.vo.QueryDryRunRespVO;
import com.ontos.module.query.controller.admin.vo.QueryExecuteRespVO;
import com.ontos.module.query.controller.admin.vo.QueryPlanRespVO;
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

import static com.ontos.framework.common.pojo.CommonResult.success;

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

    public QueryController(QueryService queryService, DslCodec dslCodec) {
        this.queryService = queryService;
        this.dslCodec = dslCodec;
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
        QueryResult result = queryService.execute(queryDsl);
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
        return success(vo);
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
            QueryResult result = sub.result();
            subVO.setSubIntentId(sub.subIntentId());
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
        vo.setEstimatedCost(plan.getEstimatedCost());
        vo.setEstimatedRows(plan.getEstimatedRows());
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
