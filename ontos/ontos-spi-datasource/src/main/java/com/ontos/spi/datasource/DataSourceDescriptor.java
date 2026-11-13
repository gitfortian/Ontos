package com.ontos.spi.datasource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 数据源插件元数据（B2）：唯一的能力与配置协议，不可变。
 *
 * <p>刻意用 {@code String type} 而不是封闭枚举：第三方插件可以在不改核心模块的前提下新增类型；
 * 类型冲突与合法性由注册表在启动期 fail-fast 校验。前端连接表单由 {@link ConfigField} 驱动，
 * secret 字段必须用 {@code secret=true} 声明，脱敏由平台按声明统一处理，插件不自行输出敏感值。
 */
public record DataSourceDescriptor(String type,
                                   String displayName,
                                   String apiVersion,
                                   Set<DataSourceCapability> capabilities,
                                   List<ConfigField> configFields) {

    public static final String CURRENT_API_VERSION = "1";

    public DataSourceDescriptor {
        type = requireText(type, "type");
        displayName = normalize(displayName, type);
        apiVersion = normalize(apiVersion, CURRENT_API_VERSION);
        capabilities = capabilities == null
                ? Set.of()
                : Set.copyOf(capabilities);
        configFields = configFields == null ? List.of() : List.copyOf(configFields);
    }

    public boolean supports(DataSourceCapability capability) {
        return capability != null && capabilities.contains(capability);
    }

    /** 声明为 secret 的配置 key，供平台统一脱敏；插件不得自行判断。 */
    public Set<String> secretKeys() {
        return configFields.stream()
                .filter(ConfigField::secret)
                .map(ConfigField::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 连接配置字段：前端表单与「测试连接」请求都按 key 组织；secret 字段的值以 {@link Secret}
     * 承载，不参与日志与 toString。
     */
    public record ConfigField(String key, String label, boolean required, boolean secret,
                              String placeholder) {
        public ConfigField {
            key = requireText(key, "configField.key");
            label = requireText(label, "configField.label");
            placeholder = normalize(placeholder, null);
        }
    }

    /** 便捷构建：把若干能力组成不可变集合。 */
    public static Set<DataSourceCapability> capabilities(DataSourceCapability... values) {
        return Stream.of(values).filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
