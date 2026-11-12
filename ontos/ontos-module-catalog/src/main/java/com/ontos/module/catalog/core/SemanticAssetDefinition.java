package com.ontos.module.catalog.core;

import java.util.Map;

import com.ontos.framework.common.util.json.JsonUtils;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_DEFINITION_INVALID;

/**
 * 草稿 definition 的顶层字段读取（B1）：只负责「是不是 JSON 对象、id/name/summary 取得到」。
 *
 * <p>字段级结构与引用完整性不在这里判定——那是 {@code ManifestStructureValidator} 的职责，
 * 本类刻意保持薄，避免管理面出现第二套校验规则。
 */
public final class SemanticAssetDefinition {

    private SemanticAssetDefinition() {
    }

    /** 解析为 JSON 对象；空串、非对象、非法 JSON 一律拒绝。 */
    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            throw exception(ASSET_DEFINITION_INVALID, "definition 为空");
        }
        Map<String, Object> parsed;
        try {
            parsed = JsonUtils.parseMap(json);
        } catch (RuntimeException ex) {
            throw exception(ASSET_DEFINITION_INVALID, "definition 不是合法 JSON 对象");
        }
        if (parsed == null) {
            throw exception(ASSET_DEFINITION_INVALID, "definition 不是 JSON 对象");
        }
        return parsed;
    }

    public static String requireId(Map<String, Object> definition) {
        String id = text(definition, "id");
        if (id == null) {
            throw exception(ASSET_DEFINITION_INVALID, "definition.id 必填");
        }
        return id;
    }

    public static String requireName(Map<String, Object> definition) {
        String name = text(definition, "name");
        if (name == null) {
            throw exception(ASSET_DEFINITION_INVALID, "definition.name 必填");
        }
        return name;
    }

    public static String summary(Map<String, Object> definition) {
        String summary = text(definition, "summary");
        return summary == null ? "" : summary;
    }

    private static String text(Map<String, Object> definition, String key) {
        Object value = definition.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
