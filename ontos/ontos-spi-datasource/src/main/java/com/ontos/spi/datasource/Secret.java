package com.ontos.spi.datasource;

import java.util.Objects;

/**
 * 敏感值包装（B2）：唯一目的是让 secret 值在任何 {@code toString()} / 日志路径下不可见。
 *
 * <p>record 自动生成的 {@code toString} 会调用组件的 {@code toString}，因此把组件换成
 * {@link Secret} 后，{@link DataSourceConnection} 的默认 toString 输出的就是 {@code ***}。
 * 取真值只能显式调用 {@link #value()}，调用点必须避免落日志。
 */
public record Secret(String value) {

    public Secret {
        Objects.requireNonNull(value, "secret value must not be null");
    }

    @Override
    public String toString() {
        return "***";
    }
}
