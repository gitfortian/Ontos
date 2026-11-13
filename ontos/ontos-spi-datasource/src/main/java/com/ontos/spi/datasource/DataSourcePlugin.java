package com.ontos.spi.datasource;

import java.time.Duration;
import java.util.Map;

/**
 * 数据源插件稳定契约（B2 Phase 1）。
 *
 * <p>实现通过 {@code META-INF/services/com.ontos.spi.datasource.DataSourcePlugin} 注册，
 * 平台用 {@code ServiceLoader} 发现，业务代码不得硬编码具体插件类。
 *
 * <p>三条硬边界：
 * <ol>
 *   <li>插件不生成、不执行 SQL——查询执行是 Ontos 编译链的专属职责；</li>
 *   <li>插件不接触租户 / RLS 上下文——连接仅用于元数据与连通性验证；</li>
 *   <li>异常必须用 {@link DataSourcePluginException} 并标注 {@code Operation}，消息不含 secret。</li>
 * </ol>
 *
 * <p>实例会被注册表单例复用，实现必须无请求级可变状态；{@link DataSourceCatalog} 可以按调用创建。
 */
public interface DataSourcePlugin {

    /** 插件类型标识（如 {@code postgresql}）；全局唯一，由注册表查重。 */
    String type();

    /** 不可变元数据：能力、配置字段与 apiVersion。 */
    DataSourceDescriptor descriptor();

    /**
     * 解析并规范化连接配置；非法参数抛
     * {@code DataSourcePluginException(Operation.PARAMETER)}。
     */
    DataSourceConnection parse(Map<String, String> config);

    /**
     * 测试连通性；失败抛 {@code DataSourcePluginException(Operation.CONNECTIVITY)}。
     * timeout 必须生效，不得无限等待。
     */
    void testConnection(DataSourceConnection connection, Duration timeout);

    /** 创建 Catalog 元数据读取器；失败抛 {@code DataSourcePluginException(Operation.CATALOG)}。 */
    DataSourceCatalog catalog(DataSourceConnection connection);

    /** 是否声明某能力；以 {@link DataSourceDescriptor} 为唯一事实源。 */
    default boolean supports(DataSourceCapability capability) {
        DataSourceDescriptor descriptor = descriptor();
        return descriptor != null && descriptor.supports(capability);
    }
}
