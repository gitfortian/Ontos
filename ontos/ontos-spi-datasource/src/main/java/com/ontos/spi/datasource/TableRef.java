package com.ontos.spi.datasource;

/**
 * 表引用（B2）：schema + table 二段定位。
 *
 * <p>刻意不允许按点拆分标识符——三段名（database/schema/table）由具体插件在自己的连接
 * 语义里处理，SPI 层只保留最小公共部分，避免把某个方言的命名习惯变成全局约定。
 */
public record TableRef(String schema, String table) {

    public TableRef {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("schema must not be blank");
        }
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must not be blank");
        }
    }
}
