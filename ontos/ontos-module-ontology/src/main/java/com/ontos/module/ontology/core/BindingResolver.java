package com.ontos.module.ontology.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.ontos.module.ontology.enums.ErrorCodeConstants;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 资产 → 物理绑定解析器（B0）：形态判断的唯一收口点。
 *
 * <p><b>为什么需要它</b>：v20 起指标/维度可能带 {@code objectId + propertyId} 而非
 * {@code table + column}。若让编译器各调用点自行 if 判断，形态分支会散落十余份，
 * 漏判一处就是静默错列。本类把「取表、取列、判日期」统一为一次解析，调用方不感知形态。
 *
 * <p><b>v20 的 FROM 归属</b>：聚合属性所在表必须等于对象主表（{@code identityProperty}
 * 所在表）。不等即拒绝——跨表对象的聚合需要 Model 层承载，不在 B0 范围。
 *
 * <p><b>线程安全</b>：实例持可变缓存，仅限单次编译内使用，不得跨请求共享。
 */
public final class BindingResolver {

    /** 可承载时间窗的属性类型（小写比较），与 {@code TimeDimensionResolver} 原口径一致。 */
    private static final Set<String> DATE_TYPES = Set.of("date", "datetime", "timestamp", "timestamptz");

    private final Map<String, OntologySnapshot.Property> propertyById;
    private final Map<String, OntologySnapshot.Concept> conceptById;
    private final Map<String, Binding> metricCache = new HashMap<>();
    private final Map<String, Binding> dimensionCache = new HashMap<>();

    private BindingResolver(OntologySnapshot ontology) {
        this.propertyById = ontology == null ? Map.of() : ontology.propertyIndex();
        this.conceptById = ontology == null ? Map.of() : ontology.conceptIndex();
    }

    public static BindingResolver of(PublishedManifest manifest) {
        return new BindingResolver(manifest == null ? null : manifest.getOntology());
    }

    public static BindingResolver of(OntologySnapshot ontology) {
        return new BindingResolver(ontology);
    }

    /** 解析结果：{@code table} 供 FROM/JOIN 用；{@code column} 恒为限定名（含表前缀）。 */
    public record Binding(String table, String column) {
    }

    public SemanticManifest.BindingForm formOf(SemanticManifest.Metric metric) {
        return metric.bindingForm();
    }

    public SemanticManifest.BindingForm formOf(SemanticManifest.Dimension dimension) {
        return dimension.bindingForm();
    }

    /** 指标的聚合属性所在表（v20 含「= 对象主表」断言）。 */
    public String tableOf(SemanticManifest.Metric metric) {
        return metricBinding(metric).table();
    }

    /** 指标的限定列名（{@code table.column}），直接喂 SQL 编译器。 */
    public String qualifiedColumnOf(SemanticManifest.Metric metric) {
        return metricBinding(metric).column();
    }

    public String tableOf(SemanticManifest.Dimension dimension) {
        return dimensionBinding(dimension).table();
    }

    public String qualifiedColumnOf(SemanticManifest.Dimension dimension) {
        return dimensionBinding(dimension).column();
    }

    /**
     * 维度是否可承载时间窗（v20 直接看属性 valueType；v19 按物理列反查属性）。
     *
     * <p>反查失败返回 false——不猜列、不默认取第一个维度，与原 fail-closed 口径一致。
     */
    public boolean isDateBinding(SemanticManifest.Dimension dimension) {
        if (dimension == null) {
            return false;
        }
        SemanticManifest.BindingForm form = dimension.bindingForm();
        if (form == SemanticManifest.BindingForm.V20_SEMANTIC) {
            OntologySnapshot.Property property = propertyById.get(dimension.getPropertyId());
            return property != null && isDateType(property.getValueType());
        }
        if (form != SemanticManifest.BindingForm.V19_PHYSICAL) {
            return false;
        }
        String qualified = dimensionBinding(dimension).column();
        return propertyById.values().stream()
                .anyMatch(property -> property.getColumn() != null
                        && property.getColumn().equalsIgnoreCase(qualified)
                        && isDateType(property.getValueType()));
    }

    // ---------- 内部：按形态解析 ----------

    private Binding metricBinding(SemanticManifest.Metric metric) {
        String cacheKey = metric.getId() == null ? "" : metric.getId();
        Binding cached = metricCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Binding binding = switch (metric.bindingForm()) {
            case V19_PHYSICAL -> new Binding(metric.getTable(), qualify(metric.getTable(), metric.getColumn()));
            case V20_SEMANTIC -> resolveSemantic(metric.getObjectId(), metric.getPropertyId(), true);
            case RULE_ONLY -> throw exception(ErrorCodeConstants.ASSET_NOT_FOUND,
                    "布尔判定指标无物理绑定: " + metric.getId());
            case REJECT -> throw exception(ErrorCodeConstants.ASSET_NOT_FOUND,
                    "物理绑定形态非法: " + metric.getId());
        };
        metricCache.put(cacheKey, binding);
        return binding;
    }

    private Binding dimensionBinding(SemanticManifest.Dimension dimension) {
        String cacheKey = dimension.getId() == null ? "" : dimension.getId();
        Binding cached = dimensionCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Binding binding = switch (dimension.bindingForm()) {
            case V19_PHYSICAL -> new Binding(dimension.getTable(),
                    qualify(dimension.getTable(), dimension.getColumn()));
            case V20_SEMANTIC -> resolveSemantic(dimension.getObjectId(), dimension.getPropertyId(), false);
            case RULE_ONLY, REJECT -> throw exception(ErrorCodeConstants.ASSET_NOT_FOUND,
                    "维度物理绑定形态非法: " + dimension.getId());
        };
        dimensionCache.put(cacheKey, binding);
        return binding;
    }

    /**
     * v20：由对象属性定位物理列。
     *
     * @param assertObjectTable 仅指标为 true——指标承担 FROM，聚合属性必须与对象主表同表；
     *                          维度只参与 JOIN（可达即可），不做该断言（B0/R7 的口径）
     */
    private Binding resolveSemantic(String objectId, String propertyId, boolean assertObjectTable) {
        OntologySnapshot.Concept concept = conceptById.get(objectId);
        if (concept == null) {
            throw exception(ErrorCodeConstants.ASSET_NOT_FOUND, "业务对象不存在: " + objectId);
        }
        // propertyId 为空 = 对象级计数：取对象标识属性作为计数列
        String effectivePropertyId = blank(propertyId) ? concept.getIdentityProperty() : propertyId;
        OntologySnapshot.Property property = propertyById.get(effectivePropertyId);
        if (property == null) {
            throw exception(ErrorCodeConstants.ASSET_NOT_FOUND,
                    "对象属性不存在: " + effectivePropertyId);
        }
        String table = tableOf(property.getColumn());
        if (!assertObjectTable) {
            return new Binding(table, property.getColumn());
        }
        String identityTable = identityTableOf(concept);
        if (!Objects.equals(table, identityTable)) {
            throw exception(ErrorCodeConstants.OBJECT_TABLE_MISMATCH,
                    objectId + " / " + effectivePropertyId
                            + "（属性表 " + table + "，主表 " + identityTable + "）");
        }
        return new Binding(table, property.getColumn());
    }

    private String identityTableOf(OntologySnapshot.Concept concept) {
        OntologySnapshot.Property identity = propertyById.get(concept.getIdentityProperty());
        return identity == null ? null : tableOf(identity.getColumn());
    }

    /** 限定列名：裸列名补表前缀，已含点则原样返回（与 {@code SqlCompiler.assetColumn} 同口径）。 */
    private static String qualify(String table, String column) {
        if (column == null) {
            return null;
        }
        if (column.contains(".") || blank(table)) {
            return column;
        }
        return table + "." + column;
    }

    /** "table.column" / "schema.table.column" → 表限定名；裸列名返回 null。 */
    private static String tableOf(String column) {
        if (column == null) {
            return null;
        }
        int lastDot = column.lastIndexOf('.');
        return lastDot <= 0 ? null : column.substring(0, lastDot);
    }

    private static boolean isDateType(String valueType) {
        return valueType != null && DATE_TYPES.contains(valueType.toLowerCase(Locale.ROOT));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
