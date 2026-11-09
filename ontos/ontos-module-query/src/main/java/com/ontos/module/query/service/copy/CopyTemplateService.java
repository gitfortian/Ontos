package com.ontos.module.query.service.copy;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.query.enums.ErrorCodeConstants.COPY_TEMPLATE_MISSING;

/**
 * 原因码 → 用户文案模板（M3/W4，v1.4 §7.7 / §5.12）。
 *
 * <p>三条硬边界：
 * <ol>
 *   <li>文案只来自已发布清单的模板资产，服务端不拼字符串；</li>
 *   <li>占位符按发布期白名单渲染——声明了但调用方没给值属于「渲染失败」，
 *       回退 {@code GENERIC} 通用模板（记 WARN），绝不把带 {@code {x}} 的原文透给用户；</li>
 *   <li>模板文本里未声明的花括号序列（R-26：发布期不违规、渲染期不命中白名单）
 *       按**字面量**原样输出——那是文案的一部分，不是待填充槽。</li>
 * </ol>
 *
 * <p>查找顺序：reasonCode + locale → reasonCode + 任意 locale → GENERIC（同规则）。
 * 全部落空才是 {@code COPY_TEMPLATE_MISSING}（v7 等旧清单没有 GENERIC 模板时如实报缺）。
 */
@Slf4j
@Service
public class CopyTemplateService {

    private static final String FALLBACK_REASON = "GENERIC";
    private static final String DEFAULT_LOCALE = "zh-CN";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    private final ManifestService manifestService;

    public CopyTemplateService(ManifestService manifestService) {
        this.manifestService = manifestService;
    }

    /**
     * @param locale    缺省 zh-CN；该原因码没有对应 locale 时回退同原因码任意 locale
     * @param placeholderValues 占位符值（String.valueOf 后替换）
     */
    public RenderedCopy render(String reasonCode, String locale, Map<String, Object> placeholderValues) {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null) {
            throw exception(COPY_TEMPLATE_MISSING, reasonCode + "（无生效清单）");
        }
        String wantedLocale = locale == null || locale.isBlank() ? DEFAULT_LOCALE : locale;
        Map<String, Object> values = placeholderValues == null ? Map.of() : placeholderValues;

        ManifestPayload.CopyTemplate template = find(manifest, reasonCode, wantedLocale);
        boolean fallback = false;
        if (template == null) {
            template = requireGeneric(manifest);
            fallback = true;
            log.warn("[render][原因码 {} 无模板，回退 GENERIC]", reasonCode);
        }
        String text;
        try {
            text = renderText(template, values);
        } catch (ServiceException ex) {
            // 声明了占位符但调用方没给值：不把原文透给用户，回退 GENERIC（GENERIC 自身失败则如实报缺）
            if (fallback) {
                throw ex;
            }
            log.warn("[render][模板 {} 渲染失败（{}），回退 GENERIC]", template.getTemplateId(), ex.getMessage());
            template = requireGeneric(manifest);
            text = renderText(template, values);
            fallback = true;
        }
        return new RenderedCopy(template.getTemplateId(), template.effectiveReasonCode(),
                template.getLocale(), text, template.getVersion(), fallback);
    }

    private ManifestPayload.CopyTemplate requireGeneric(PublishedManifest manifest) {
        ManifestPayload.CopyTemplate generic = find(manifest, FALLBACK_REASON, DEFAULT_LOCALE);
        if (generic == null) {
            // v7 等旧清单没有 GENERIC 模板：fail-closed 如实报缺，不硬编码文案
            throw exception(COPY_TEMPLATE_MISSING, FALLBACK_REASON);
        }
        return generic;
    }

    private ManifestPayload.CopyTemplate find(PublishedManifest manifest, String reasonCode, String locale) {
        List<ManifestPayload.CopyTemplate> templates = manifest.getCopyTemplates() == null
                ? List.of() : manifest.getCopyTemplates();
        ManifestPayload.CopyTemplate anyLocale = null;
        for (ManifestPayload.CopyTemplate template : templates) {
            if (!reasonCode.equals(template.effectiveReasonCode())) {
                continue;
            }
            if (locale.equals(template.getLocale())) {
                return template;
            }
            anyLocale = anyLocale == null ? template : anyLocale;
        }
        return anyLocale;
    }

    /**
     * 渲染：只替换「发布期声明过」的占位符；声明了但未给值 → 抛出（调用方回退 GENERIC）；
     * 文本里未声明的花括号序列按字面量输出（R-26）。
     */
    private String renderText(ManifestPayload.CopyTemplate template, Map<String, Object> values) {
        List<String> allowed = template.getAllowedPlaceholders() == null
                ? List.of() : template.getAllowedPlaceholders();
        for (String name : allowed) {
            if (!values.containsKey(name)) {
                throw exception(COPY_TEMPLATE_MISSING,
                        template.getTemplateId() + " 缺少占位符值 " + name);
            }
        }
        Matcher matcher = PLACEHOLDER.matcher(template.getText());
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = allowed.contains(name)
                    ? String.valueOf(values.get(name))
                    : matcher.group(); // R-26：未声明的花括号是文案的一部分，按字面量保留
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /** 渲染结果（fallback=true 表示走了 GENERIC 兜底，前端可据此提示「未知原因」）。 */
    public record RenderedCopy(String templateId, String reasonCode, String locale,
                               String text, String version, boolean fallback) {
    }
}
