package com.ontos.spi.datasource;

/** 列元数据（B2）：{@code dataType} 为来源库的类型名原样透传，不做跨库归一。 */
public record ColumnMeta(String name, String dataType, boolean nullable) {

    public ColumnMeta {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("column name must not be blank");
        }
        dataType = dataType == null || dataType.isBlank() ? "unknown" : dataType.trim();
    }
}
