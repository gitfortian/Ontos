package com.ontos.module.datasource.service;

import com.ontos.spi.datasource.ColumnMeta;

/** 列元数据视图（B2）：dataType 为来源库类型名原样透传。 */
public record DataSourceColumnView(String name, String dataType, boolean nullable) {

    public static DataSourceColumnView of(ColumnMeta column) {
        return new DataSourceColumnView(column.name(), column.dataType(), column.nullable());
    }
}
