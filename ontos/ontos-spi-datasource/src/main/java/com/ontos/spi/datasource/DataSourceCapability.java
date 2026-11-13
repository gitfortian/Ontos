package com.ontos.spi.datasource;

/**
 * 数据源插件能力（B2）。调用方必须先查能力再调用，不得以「调用后抛异常」作为能力发现方式。
 *
 * <p>Phase 1 只声明两件事：连接测试与 Catalog 元数据读取。刻意不提供 SQL 执行能力——
 * SQL 由 Ontos 编译并校验后经 {@code QueryExecutor} 只读执行，插件无权生成或执行查询。
 */
public enum DataSourceCapability {

    /** 可测试连接（parse 后的连接可达）。 */
    CONNECTION_TEST,

    /** 可读取库 / schema / 表 / 列元数据。 */
    CATALOG_METADATA
}
