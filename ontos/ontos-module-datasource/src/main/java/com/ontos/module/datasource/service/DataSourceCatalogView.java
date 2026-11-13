package com.ontos.module.datasource.service;

import com.ontos.spi.datasource.TableMeta;

import java.util.List;

/**
 * 真实库目录视图（B2 Phase 1）：来自数据源插件的 schema / 表元数据。
 *
 * <p>{@code schema} 是本次实际浏览的 schema（未显式指定时取连接默认值）。
 */
public record DataSourceCatalogView(String type,
                                    List<String> schemas,
                                    String schema,
                                    List<TableMeta> tables) {
}
