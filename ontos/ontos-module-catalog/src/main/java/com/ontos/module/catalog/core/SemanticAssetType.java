package com.ontos.module.catalog.core;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_TYPE_INVALID;

/**
 * 语义资产类型（B1）：对外 wire 值与 ID 前缀的唯一映射。
 *
 * <p>ID 前缀有两处历史命名与类型名不一致，显式登记在此，禁止各调用点自行判断：
 * <ul>
 *   <li>{@code relation} 的 ID 前缀是 {@code ontology.}（沿用 v1.4 关系命名，v22 清单已在用）；</li>
 *   <li>{@code dimension} 的 ID 前缀是 {@code dim.}。</li>
 * </ul>
 *
 * <p>前缀同时用于草稿表 CHECK 约束与草稿校验的 violation 过滤（校验器 location 为
 * {@code 类型名 + "." + 资产 ID}，如 {@code metric.metric.gmv}）。
 */
public enum SemanticAssetType {

    CONCEPT("concept", "concept."),
    PROPERTY("property", "property."),
    RELATION("relation", "ontology."),
    METRIC("metric", "metric."),
    DIMENSION("dimension", "dim.");

    private final String wire;
    private final String idPrefix;

    SemanticAssetType(String wire, String idPrefix) {
        this.wire = wire;
        this.idPrefix = idPrefix;
    }

    public String wire() {
        return wire;
    }

    public String idPrefix() {
        return idPrefix;
    }

    /** 严格按 wire 值解析，不做大小写归一化——非法值直接拒绝。 */
    public static SemanticAssetType of(String wire) {
        for (SemanticAssetType type : values()) {
            if (type.wire.equals(wire)) {
                return type;
            }
        }
        throw exception(ASSET_TYPE_INVALID, wire);
    }

    /** ID 前缀是否与类型匹配；null / 空串一律不匹配。 */
    public boolean matchesId(String id) {
        return id != null && id.startsWith(idPrefix);
    }

    /** 校验器 location 前缀：{@code 类型名 + "." + 资产 ID}（与 ManifestStructureValidator 拼接方式一致）。 */
    public String locationPrefix(String id) {
        return wire + "." + id;
    }
}
