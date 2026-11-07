package com.ontos.module.query.controller.admin;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.module.query.controller.admin.vo.QueryCompileRespVO;
import com.ontos.module.query.controller.admin.vo.QueryExecuteRespVO;
import com.ontos.module.query.core.compiler.CompiledQuery;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.query.core.executor.QueryResult;
import com.ontos.module.query.service.QueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
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

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/compile")
    @Operation(summary = "编译 DSL（dry-plan，不执行）")
    public CommonResult<QueryCompileRespVO> compile(@RequestBody QueryDsl queryDsl) {
        CompiledQuery compiled = queryService.compile(queryDsl);
        QueryCompileRespVO vo = new QueryCompileRespVO();
        vo.setManifestId(compiled.getManifestId());
        vo.setContentHash(compiled.getContentHash());
        vo.setMetricId(compiled.getMetricId());
        vo.setDimensions(compiled.getDimensions());
        vo.setDialect(compiled.getDialect());
        vo.setSql(compiled.getSql());
        vo.setBindValues(compiled.getBindValues());
        vo.setAppliedLimit(compiled.getAppliedLimit());
        return success(vo);
    }

    @PostMapping("/execute")
    @Operation(summary = "编译并执行 DSL")
    public CommonResult<QueryExecuteRespVO> execute(@RequestBody QueryDsl queryDsl) {
        QueryResult result = queryService.execute(queryDsl);
        QueryExecuteRespVO vo = new QueryExecuteRespVO();
        vo.setManifestId(result.getManifestId());
        vo.setContentHash(result.getContentHash());
        vo.setColumns(result.getColumns());
        vo.setRows(result.getRows());
        vo.setRowCount(result.getRowCount());
        vo.setTruncated(result.isTruncated());
        vo.setElapsedMs(result.getElapsedMs());
        return success(vo);
    }

}
