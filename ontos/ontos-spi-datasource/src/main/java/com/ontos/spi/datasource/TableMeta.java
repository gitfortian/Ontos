package com.ontos.spi.datasource;

/** 表元数据（B2）：{@code type} 取 information_schema 语义（BASE TABLE / VIEW 等），插件原样透传。 */
public record TableMeta(String name, String type) {

    public TableMeta {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("table name must not be blank");
        }
        type = type == null || type.isBlank() ? "BASE TABLE" : type.trim();
    }
}
