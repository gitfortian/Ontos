package com.ontos.module.datasource.service;

import java.util.List;
import java.util.Set;

/**
 * 数据源目录视图（B2 Phase 1）：管理面接口的直接返回体。
 *
 * <p>{@code manifestTables} 是清单属性反推的「语义模型已引用表」，用于和真实目录对照；
 * {@code jdbcUrl} 已在服务端脱敏；插件不返回任何 secret。执行链不消费本视图。
 */
public record DataSourceSummaryView(String id,
                                    String name,
                                    String jdbcUrl,
                                    boolean readOnly,
                                    String schema,
                                    String manifestId,
                                    List<ManifestTable> manifestTables,
                                    List<PluginView> plugins) {

    /** 清单引用的物理表及其属性分布。 */
    public record ManifestTable(String name, int propertyCount, List<String> propertyIds) {
    }

    /** 已注册插件：type 全局唯一，capabilities 是唯一能力事实源；configFields 驱动前端注册表单。 */
    public record PluginView(String type, String displayName, Set<String> capabilities,
                             List<ConfigFieldView> configFields) {
    }

    /** 连接配置字段声明（key/label/required/secret/placeholder）。 */
    public record ConfigFieldView(String key, String label, boolean required, boolean secret,
                                  String placeholder) {
    }
}
