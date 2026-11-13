package com.ontos.spi.datasource;

import java.util.Map;
import java.util.Objects;

/**
 * 插件解析后的标准连接参数（B2）。
 *
 * <p>由插件 {@link DataSourcePlugin#parse} 从配置规范化产出；{@code password} 一律以
 * {@link Secret} 承载——record 默认 toString 会调用组件 toString，从而保证密码在任何
 * 日志路径下输出 {@code ***}。连接参数本身不承载租户 / RLS 上下文：那是 Ontos 执行通道的职责。
 */
public record DataSourceConnection(String jdbcUrl,
                                   String username,
                                   Secret password,
                                   String database,
                                   String schema,
                                   Map<String, String> properties) {

    public DataSourceConnection {
        jdbcUrl = require(jdbcUrl, "jdbcUrl");
        username = normalize(username);
        database = normalize(database);
        schema = normalize(schema);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        Objects.requireNonNull(password, "password must not be null (use empty Secret for匿名)");
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
