package com.ontos.module.datasource.plugin;

import com.ontos.module.datasource.enums.ErrorCodeConstants;import com.ontos.spi.datasource.DataSourceDescriptor;
import com.ontos.spi.datasource.DataSourcePlugin;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 数据源插件注册表（B2）：{@code ServiceLoader} 发现 + 启动期 fail-fast 校验。
 *
 * <p>照抄 data-ops 的注册姿势并收紧两点：类型用 {@code String}（不锁死封闭枚举），
 * 校验失败只允许发生在启动期（重复 type / apiVersion 不匹配 / 未声明能力 / 元数据类型不一致），
 * 运行期查询未命中统一 {@code DATASOURCE_PLUGIN_NOT_FOUND}。业务代码不得出现具体插件类。
 */
@Component
public class DataSourcePluginRegistry {

    private Map<String, DataSourcePlugin> plugins = Map.of();

    /** Spring 入口：无参构造 + @PostConstruct 发现。 */
    public DataSourcePluginRegistry() {
    }

    /** Spring 入口：从 classpath 发现插件；测试可用包级构造器直接注入插件集合。 */
    @PostConstruct
    void discover() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = DataSourcePluginRegistry.class.getClassLoader();
        }
        this.plugins = collect(ServiceLoader.load(DataSourcePlugin.class, loader).stream()
                .map(ServiceLoader.Provider::get)
                .toList());
    }

    /** 测试与特殊装配入口：直接注入插件集合（仍须经 collect 校验的场景请用 collect）。 */
    public DataSourcePluginRegistry(Map<String, DataSourcePlugin> plugins) {
        this.plugins = Map.copyOf(plugins);
    }

    /** 校验 + 查重 + 收敛为不可变映射；任何违规都在启动期抛出，不带病运行。 */
    public Map<String, DataSourcePlugin> collect(List<DataSourcePlugin> candidates) {
        Map<String, DataSourcePlugin> discovered = new LinkedHashMap<>();
        for (DataSourcePlugin plugin : candidates) {
            validate(plugin);
            DataSourcePlugin existing = discovered.putIfAbsent(plugin.type(), plugin);
            if (existing != null) {
                throw new IllegalStateException("重复的数据源插件类型 " + plugin.type() + ": "
                        + existing.getClass().getName() + " vs " + plugin.getClass().getName());
            }
        }
        return Map.copyOf(discovered);
    }

    public DataSourcePlugin get(String type) {
        DataSourcePlugin plugin = plugins.get(type);
        if (plugin == null) {
            throw exception(ErrorCodeConstants.DATASOURCE_PLUGIN_NOT_FOUND,
                    type == null ? "null" : type);
        }
        return plugin;
    }

    public List<DataSourcePlugin> all() {
        return List.copyOf(plugins.values());
    }

    private void validate(DataSourcePlugin plugin) {
        if (plugin.type() == null || plugin.type().isBlank()) {
            throw new IllegalStateException(
                    "数据源插件 type 不能为空: " + plugin.getClass().getName());
        }
        DataSourceDescriptor descriptor = plugin.descriptor();
        if (descriptor == null) {
            throw new IllegalStateException(
                    "数据源插件 descriptor 不能为空: " + plugin.getClass().getName());
        }
        if (!descriptor.type().equals(plugin.type())) {
            throw new IllegalStateException("数据源插件 descriptor.type 与 type() 不一致: "
                    + plugin.type() + " vs " + descriptor.type());
        }
        if (!DataSourceDescriptor.CURRENT_API_VERSION.equals(descriptor.apiVersion())) {
            throw new IllegalStateException("不支持的数据源插件 apiVersion "
                    + descriptor.apiVersion() + ": " + plugin.type());
        }
        if (descriptor.capabilities().isEmpty()) {
            throw new IllegalStateException("数据源插件未声明能力: " + plugin.type());
        }
    }
}
