package com.ontos.module.datasource.controller.admin.view;

import java.util.List;
import java.util.Map;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.module.datasource.service.view.DataViewService;
import com.ontos.module.ontology.service.publish.PublishGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.ontos.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 数据视图资产（B3）：扫描固化 / 漂移检测 / 退役。
 * 鉴权走 {@link PublishGuard} 管理面闸门；身份只来自认证上下文。
 */
@Tag(name = "管理后台 - 数据视图资产")
@RestController
@RequestMapping("/api/v1/catalog/data-views")
public class DataViewController {

    private final DataViewService service;
    private final PublishGuard publishGuard;

    public DataViewController(DataViewService service, PublishGuard publishGuard) {
        this.service = service;
        this.publishGuard = publishGuard;
    }

    public record ScanReqVO(String configId, String schema, String table, String name) {
    }

    @PostMapping("/scan")
    @Operation(summary = "扫描固化：拉列 → 规范化 ID → upsert（同源同表覆盖更新）")
    public CommonResult<DataViewService.ScanResult> scan(@RequestBody ScanReqVO request) {
        publishGuard.requirePublishAllowed("datasource.view.scan");
        return success(service.scan(request.configId(), request.schema(), request.table(),
                request.name(), publishGuard.currentPublisher()));
    }

    @GetMapping
    @Operation(summary = "数据视图列表（含 retired，前端按状态过滤）")
    public CommonResult<List<DataViewService.ViewVO>> list() {
        publishGuard.requirePublishAllowed("datasource.view.list");
        return success(service.list());
    }

    @GetMapping("/{id}")
    @Operation(summary = "视图详情（含列快照）")
    public CommonResult<DataViewService.ViewVO> get(@PathVariable("id") String id) {
        publishGuard.requirePublishAllowed("datasource.view.get");
        return success(service.get(id));
    }

    @PostMapping("/{id}/rescan")
    @Operation(summary = "重扫描：diff + referencedRemoved 引用检测")
    public CommonResult<DataViewService.ScanResult> rescan(@PathVariable("id") String id) {
        publishGuard.requirePublishAllowed("datasource.view.rescan");
        return success(service.rescan(id, publishGuard.currentPublisher()));
    }

    @PostMapping("/{id}/retire")
    @Operation(summary = "退役：active 清单仍引用时 fail-closed")
    public CommonResult<Boolean> retire(@PathVariable("id") String id) {
        publishGuard.requirePublishAllowed("datasource.view.retire");
        service.retire(id);
        return success(true);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "重新激活：同源同表不得已有 active 视图")
    public CommonResult<Boolean> activate(@PathVariable("id") String id) {
        publishGuard.requirePublishAllowed("datasource.view.activate");
        service.activate(id);
        return success(true);
    }
}
