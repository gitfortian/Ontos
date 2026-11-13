package com.ontos.module.datasource.controller.admin;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.module.datasource.service.DataSourceCatalogService;
import com.ontos.module.datasource.service.DataSourceCatalogView;
import com.ontos.module.datasource.service.DataSourceColumnView;
import com.ontos.module.datasource.service.DataSourceSummaryView;
import com.ontos.module.ontology.service.publish.PublishGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static com.ontos.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 数据源目录（B2 Phase 1，只读 + 连通性测试）。
 *
 * <p>鉴权走 {@link PublishGuard} 管理面闸门：dev profile 放行，其余 403（触发点 ADR-0021）。
 * 刻意不提供：保存连接、执行 SQL、写库；多源注册与 datasetId 化属 Phase 2。
 */
@Tag(name = "管理后台 - 数据源目录")
@RestController
@RequestMapping("/api/v1/catalog/data-sources")
public class DataSourceManagementController {

    private final DataSourceCatalogService catalogService;
    private final PublishGuard publishGuard;

    public DataSourceManagementController(DataSourceCatalogService catalogService,
                                          PublishGuard publishGuard) {
        this.catalogService = catalogService;
        this.publishGuard = publishGuard;
    }

    @GetMapping
    @Operation(summary = "查询当前数据源、清单引用表与已注册插件")
    public CommonResult<DataSourceSummaryView> summary() {
        publishGuard.requirePublishAllowed("datasource.catalog.summary");
        return success(catalogService.summary());
    }

    @GetMapping("/current/catalog")
    @Operation(summary = "真实库目录：schema 列表与指定 schema 的表")
    public CommonResult<DataSourceCatalogView> browse(
            @RequestParam(value = "schema", required = false) String schema) {
        publishGuard.requirePublishAllowed("datasource.catalog.browse");
        return success(catalogService.browse(schema));
    }

    @GetMapping("/current/columns")
    @Operation(summary = "指定表的列元数据")
    public CommonResult<List<DataSourceColumnView>> columns(
            @RequestParam("schema") String schema,
            @RequestParam("table") String table) {
        publishGuard.requirePublishAllowed("datasource.catalog.columns");
        return success(catalogService.columns(schema, table));
    }

    @PostMapping("/current/test")
    @Operation(summary = "测试当前查询库配置的连通性（不接收配置、不保存）")
    public CommonResult<Boolean> testCurrent() {
        publishGuard.requirePublishAllowed("datasource.catalog.test-current");
        catalogService.testCurrent();
        return success(true);
    }

    @PostMapping("/{type}/test")
    @Operation(summary = "测试连接（不保存配置）")
    public CommonResult<Boolean> test(
            @PathVariable("type") String type,
            @RequestBody(required = false) Map<String, String> config) {
        publishGuard.requirePublishAllowed("datasource.catalog.test");
        catalogService.test(type, config);
        return success(true);
    }
}
