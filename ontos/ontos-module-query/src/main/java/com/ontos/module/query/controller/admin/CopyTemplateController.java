package com.ontos.module.query.controller.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.module.query.controller.admin.vo.CopyTemplateVO;
import com.ontos.module.query.service.copy.CopyTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.ontos.framework.common.pojo.CommonResult.success;

/**
 * 原因码文案端点（M3/W4，v1.4 §7.7）。
 *
 * <p>独立于错误响应：错误契约保持 {@code code/msg/data} 稳定，文案版本可独立升级
 * （M3 规格 §8 决策 3）。占位符值通过 query 参数传入（除 {@code locale} 外，
 * 其余参数都按占位符名对待，模板未声明的参数会被忽略）。
 */
@Tag(name = "管理后台 - 原因码文案")
@RestController
@RequestMapping("/api/v1/copy-templates")
public class CopyTemplateController {

    private final CopyTemplateService copyTemplateService;

    public CopyTemplateController(CopyTemplateService copyTemplateService) {
        this.copyTemplateService = copyTemplateService;
    }

    @GetMapping("/{reasonCode}")
    @Operation(summary = "按原因码取渲染文案；未知原因码回退 GENERIC（fallback=true）")
    public CommonResult<CopyTemplateVO> render(@PathVariable String reasonCode,
                                               @RequestParam Map<String, String> params) {
        Map<String, String> mutable = new LinkedHashMap<>(params);
        String locale = mutable.remove("locale");
        Map<String, Object> placeholderValues = new LinkedHashMap<>(mutable);
        CopyTemplateService.RenderedCopy rendered =
                copyTemplateService.render(reasonCode, locale, placeholderValues);
        return success(toVO(rendered));
    }

    private static CopyTemplateVO toVO(CopyTemplateService.RenderedCopy rendered) {
        CopyTemplateVO vo = new CopyTemplateVO();
        vo.setTemplateId(rendered.templateId());
        vo.setReasonCode(rendered.reasonCode());
        vo.setLocale(rendered.locale());
        vo.setText(rendered.text());
        vo.setVersion(rendered.version());
        vo.setFallback(rendered.fallback());
        return vo;
    }
}
