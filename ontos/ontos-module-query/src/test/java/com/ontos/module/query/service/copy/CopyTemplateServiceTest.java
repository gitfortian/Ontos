package com.ontos.module.query.service.copy;

import java.util.List;
import java.util.Map;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.COPY_TEMPLATE_MISSING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** M3/W4：原因码 → 文案模板的渲染与兜底（R-26 花括号字面量口径）。 */
class CopyTemplateServiceTest {

    private final ManifestService manifestService = mock(ManifestService.class);
    private final CopyTemplateService service = new CopyTemplateService(manifestService);

    private static ManifestPayload.CopyTemplate template(String id, String reason, String locale,
                                                         String text, List<String> allowed) {
        ManifestPayload.CopyTemplate template = new ManifestPayload.CopyTemplate();
        template.setTemplateId(id);
        template.setReasonCode(reason);
        template.setLocale(locale);
        template.setText(text);
        template.setAllowedPlaceholders(allowed);
        template.setVersion("1.0.0");
        return template;
    }

    private void active(List<ManifestPayload.CopyTemplate> templates) {
        PublishedManifest manifest = new PublishedManifest();
        manifest.setCopyTemplates(templates);
        when(manifestService.getActiveManifestOrNull()).thenReturn(manifest);
    }

    @Test
    void shouldRenderDeclaredPlaceholder() {
        active(List.of(template("template.reason.CALIBER_AMBIGUOUS", "CALIBER_AMBIGUOUS", "zh-CN",
                "这个指标有多个口径：{variant_aliases}。请选择。", List.of("variant_aliases"))));

        CopyTemplateService.RenderedCopy rendered = service.render("CALIBER_AMBIGUOUS", null,
                Map.of("variant_aliases", "线上口径 / 线下口径"));

        assertEquals("这个指标有多个口径：线上口径 / 线下口径。请选择。", rendered.text());
        assertFalse(rendered.fallback());
        assertEquals("1.0.0", rendered.version());
    }

    @Test
    void shouldFallBackToGenericForUnknownReason() {
        active(List.of(template("template.reason.GENERIC", "GENERIC", "zh-CN",
                "这次没能完成查询。", List.of())));

        CopyTemplateService.RenderedCopy rendered = service.render("NO_SUCH_REASON", null, Map.of());

        assertEquals("GENERIC", rendered.reasonCode());
        assertTrue(rendered.fallback());
        assertTrue(rendered.text().contains("没能完成"));
    }

    @Test
    void shouldFallBackToGenericWhenPlaceholderValueMissing() {
        // 声明了占位符但调用方没给值：不把 {x} 原文透给用户
        active(List.of(
                template("template.reason.CALIBER_AMBIGUOUS", "CALIBER_AMBIGUOUS", "zh-CN",
                        "候选：{variant_aliases}", List.of("variant_aliases")),
                template("template.reason.GENERIC", "GENERIC", "zh-CN", "兜底文案", List.of())));

        CopyTemplateService.RenderedCopy rendered = service.render("CALIBER_AMBIGUOUS", null, Map.of());

        assertEquals("兜底文案", rendered.text());
        assertTrue(rendered.fallback());
    }

    @Test
    void shouldFallBackToAnyLocaleWhenExactLocaleMissing() {
        active(List.of(template("template.reason.OUT_OF_SCOPE", "OUT_OF_SCOPE", "en-US",
                "Out of scope.", List.of())));

        CopyTemplateService.RenderedCopy rendered = service.render("OUT_OF_SCOPE", "zh-CN", Map.of());

        assertEquals("en-US", rendered.locale());
        assertFalse(rendered.fallback());
    }

    @Test
    void shouldKeepUndeclaredBracesAsLiteral() {
        // R-26：发布期只拦「allowedPlaceholders 未声明」的 ASCII 占位符；
        // 渲染期对文本里的花括号序列一律按字面量输出，不做猜测
        active(List.of(template("template.reason.NOTE", "NOTE", "zh-CN",
                "格式如 {示例} 与 {unknown_field}。", List.of())));

        CopyTemplateService.RenderedCopy rendered = service.render("NOTE", null, Map.of());

        assertEquals("格式如 {示例} 与 {unknown_field}。", rendered.text());
    }

    @Test
    void shouldFailClosedWhenNoTemplateAtAll() {
        // v7 等旧清单没有 GENERIC：如实报缺，不硬编码文案
        active(List.of());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.render("ANY", null, Map.of()));
        assertEquals(COPY_TEMPLATE_MISSING.getCode(), ex.getCode());
    }
}
