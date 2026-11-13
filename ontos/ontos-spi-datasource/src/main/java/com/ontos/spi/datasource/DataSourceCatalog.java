package com.ontos.spi.datasource;

import java.util.List;

/**
 * Catalog 元数据契约（B2 Phase 1）：只读 schema / 表 / 列。
 *
 * <p>刻意比 data-ops 的 {@code DataSourceCatalog} 窄：没有 preview / count / buildSqlTemplate /
 * resolveSql——任何「插件替平台执行查询」的口子都会绕过 DSL 校验、RLS 与审计，Phase 1 一律不开放。
 * 实现可以按调用创建实例，不假设跨线程共享；连接与语句必须在完成或失败路径关闭。
 */
public interface DataSourceCatalog extends AutoCloseable {

    /** 用户可见的 schema 列表（实现应过滤系统 schema）。 */
    List<String> schemas();

    /** 指定 schema 下的表与视图。 */
    List<TableMeta> tables(String schema);

    /** 指定表的列。 */
    List<ColumnMeta> columns(TableRef table);

    /** 释放底层连接；实现必须幂等且吞掉关闭异常。 */
    @Override
    void close();
}
