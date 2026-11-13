package com.ontos.module.datasource.controller.admin.config;

import java.util.List;
import java.util.Map;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.module.datasource.controller.admin.config.vo.DataSourceConfigRespVO;
import com.ontos.module.datasource.controller.admin.config.vo.DataSourceConfigSaveReqVO;
import com.ontos.module.datasource.service.DataSourceCatalogView;
import com.ontos.module.datasource.service.DataSourceColumnView;
import com.ontos.module.datasource.service.config.DatasourceConfigService;
import com.ontos.module.ontology.service.publish.PublishGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.ontos.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 注册数据源配置（B2 Phase 2 前半）。
 *
 * <p>鉴权走 {@link PublishGuard} 管理面闸门（与当前查询库的目录端点同口径）。身份只来自
 * 认证上下文（owner/currentPublisher），请求体不接收身份。凭据经服务端加密落库，
 * 响应永远脱敏。
 */
@Tag(name = "管理后台 - 注册数据源配置")
@RestController
@RequestMapping("/api/v1/catalog/data-sources/configs")
public class DataSourceConfigController {

    private final DatasourceConfigService service;
    private final PublishGuard publishGuard;

    public DataSourceConfigController(DatasourceConfigService service, PublishGuard publishGuard) {
        this.service = service;
        this.publishGuard = publishGuard;
    }

    @PostMapping
    @Operation(summary = "注册数据源（强制测试通过后落库）")
    public CommonResult<DataSourceConfigRespVO> create(@Valid @RequestBody DataSourceConfigSaveReqVO request) {
        publishGuard.requirePublishAllowed("datasource.config.create");
        return success(DataSourceConfigRespVO.from(service.create(request.getName(), request.getType(),
                request.getConfig(), publishGuard.currentPublisher())));
    }

    @GetMapping
    @Operation(summary = "已注册数据源列表（脱敏）")
    public CommonResult<List<DataSourceConfigRespVO>> list() {
        publishGuard.requirePublishAllowed("datasource.config.list");
        return success(service.list().stream().map(DataSourceConfigRespVO::from).toList());
    }

    @GetMapping("/{id}")
    @Operation(summary = "数据源配置详情（脱敏）")
    public CommonResult<DataSourceConfigRespVO> get(@PathVariable("id") String id) {
        publishGuard.requirePublishAllowed("datasource.config.get");
        return success(DataSourceConfigRespVO.from(service.view(id)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "编辑数据源（type 不可改；secrets 缺省保留原凭据）")
    public CommonResult<DataSourceConfigRespVO> update(
            @PathVariable("id") String id,
            @Valid @RequestBody DataSourceConfigSaveReqVO request) {
        publishGuard.requirePublishAllowed("datasource.config.update");
        return success(DataSourceConfigRespVO.from(service.update(
                id, request.getName(), request.getConfig(), request.getSecrets())));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除数据源配置")
    public CommonResult<Boolean> delete(@PathVariable("id") String id) {
        publishGuard.requirePublishAllowed("datasource.config.delete");
        service.delete(id);
        return success(true);
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "按存储配置测试连接")
    public CommonResult<Boolean> test(@PathVariable("id") String id) {
        publishGuard.requirePublishAllowed("datasource.config.test");
        service.test(id);
        return success(true);
    }

    @GetMapping("/{id}/catalog")
    @Operation(summary = "按存储配置浏览真实库目录")
    public CommonResult<DataSourceCatalogView> browse(
            @PathVariable("id") String id,
            @Parameter(description = "schema，缺省取连接配置") @RequestParam(value = "schema", required = false) String schema) {
        publishGuard.requirePublishAllowed("datasource.config.browse");
        return success(service.browse(id, schema));
    }

    @GetMapping("/{id}/columns")
    @Operation(summary = "按存储配置读表列")
    public CommonResult<List<DataSourceColumnView>> columns(
            @PathVariable("id") String id,
            @RequestParam("schema") String schema,
            @RequestParam("table") String table) {
        publishGuard.requirePublishAllowed("datasource.config.columns");
        return success(service.columns(id, schema, table));
    }
}
