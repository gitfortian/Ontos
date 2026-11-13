package com.ontos.module.ontology.core;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.Data;

/**
 * 本体快照 v2：业务对象、信息项、业务关联、业务事件、取值范围与业务限制的版本化定义。
 *
 * <p>字段语义遵循完整可落地方案 v1.4 §5.2/§5.3；对人表述遵循业务化命名规格
 * （内部标准字段名不变，name/summary/aliases 为业务化必填字段）。
 * 推理只做显式有限跳数遍历，不做隐式业务规则推导。
 */
@Data
public class OntologySnapshot {

    private String ontologyVersion;
    /** 业务对象（v1.4 术语：Ontology Class）。 */
    private List<Concept> concepts = List.of();
    /** 信息项（Ontology Property），供 lookup 投影与过滤。 */
    private List<Property> properties = List.of();
    /** 业务关联（Ontology Relation），带基数与 Join 映射。 */
    private List<Relation> relations = List.of();
    /** 业务事件（Event）：参与者、发生时间、事件类型、去重键四要素。 */
    private List<EventDef> events = List.of();
    /** 取值范围（Enum）。 */
    private List<EnumDef> enums = List.of();
    /** 业务限制（Constraint），M1 结构占位，首发可为空。 */
    private List<Constraint> constraints = List.of();
    /** 跨资产补充词（Synonym）：term → assetId。 */
    private List<Synonym> synonyms = List.of();

    public Map<String, Concept> conceptIndex() {
        return concepts.stream().collect(Collectors.toUnmodifiableMap(Concept::getId, concept -> concept));
    }

    public Map<String, Property> propertyIndex() {
        return properties.stream().collect(Collectors.toUnmodifiableMap(Property::getId, property -> property));
    }

    /** 敏感等级（v1.4 §5.3）：public / internal / PII / label。 */
    public enum Sensitivity {
        PUBLIC,
        INTERNAL,
        PII,
        LABEL
    }

    /** 业务对象（Ontology Class）。 */
    @Data
    public static class Concept {
        private String id;
        private String name;
        private String summary;
        private List<String> aliases = List.of();
        /** 主标识属性（信息项 ID），唯一性由该属性声明。 */
        private String identityProperty;
        /** 授权域（如 trade / customer / product），授权子图按此收窄。 */
        private String domain;
    }

    /** 信息项（Ontology Property）。 */
    @Data
    public static class Property {
        private String id;
        private String name;
        private String summary;
        private List<String> aliases = List.of();
        /** 所属业务对象。 */
        private String conceptId;
        private String valueType;
        private Sensitivity sensitivity = Sensitivity.INTERNAL;
        /** 是否可被 lookup 投影。 */
        private boolean lookupProjectable;
        /** 是否可作为过滤条件。 */
        private boolean filterable;
        /** 是否存在性敏感（有无本身即敏感）。 */
        private boolean existenceSensitive;
        /** 受控展示转换提示（如 date_fmt / enum_label / mask）。 */
        private List<String> displayHints = List.of();
        /** 物理列（B3 双形态）：旧口径为限定名（table.column），新口径为裸列名并搭配 viewId。LLM 不可见。 */
        private String column;
        /** 所属数据视图资产（B3 新口径）；null = 迁移期旧口径（限定名 column）。 */
        private String viewId;
    }

    /** 业务关联（Ontology Relation）。 */
    @Data
    public static class Relation {
        private String id;
        /** 关系动词（正向），如「下了」。 */
        private String name;
        private String summary;
        /** 动词变体（同义词 / 口语），进检索词库。 */
        private List<String> aliases = List.of();
        private String from;
        private String to;
        /** 形如 1:1 / 1:N / N:1 / N:M，编译器据此校验粒度。 */
        private String cardinality;
        /** 逆向动词，如「被…下单」。 */
        private String reverseLabel;
        /** Join 映射：起点列与终点列（物理列），LLM 不可见。 */
        private String fromColumn;
        private String toColumn;
        /** 是否可用于查询路径规划。 */
        private boolean queryable;
        /** 是否为已审核的 Golden Path（规划优先级高于最短路径）。 */
        private boolean goldenPath;
    }

    /** 业务事件（Event）：v1.4 §5.6 要求参与者、发生时间、事件类型、去重键四要素。 */
    @Data
    public static class EventDef {
        private String id;
        private String name;
        private String summary;
        private List<String> aliases = List.of();
        /** 参与者（业务对象 ID）。 */
        private String participantClass;
        /** 发生时间（信息项 ID）。 */
        private String timeProperty;
        /** 事件类型标识（逻辑值，funnel 的 steps 引用它）。 */
        private String eventType;
        /** 事件类型判定属性（Property ID）；ADR-0012：funnel 的物理过滤来源。 */
        private String eventTypeProperty;
        /** 判定属性上代表本事件的值；缺省取 eventType。 */
        private String eventTypeValue;
        /** 事件表上的主体标识属性（Property ID）；funnel 用它关联 subjectPropertyId。 */
        private String participantProperty;
        /** 触点渠道属性（Property ID）；attribution 触点渠道的物理来源（M6/T1.2，可空，仅触点类事件声明）。 */
        private String channelPropertyId;
        /** 去重键。 */
        private String dedupKey;
    }

    /** 取值范围（Enum）。 */
    @Data
    public static class EnumDef {
        private String id;
        private String name;
        private List<EnumValue> values = List.of();
    }

    @Data
    public static class EnumValue {
        private String value;
        private String label;
    }

    /** 业务限制（Constraint），M1 结构占位。 */
    @Data
    public static class Constraint {
        private String id;
        private String name;
        private String summary;
        /** 适用的资产 ID。 */
        private String appliesTo;
        private String kind;
        private String value;
    }

    /** 跨资产补充词（Synonym）。 */
    @Data
    public static class Synonym {
        private String term;
        private String assetId;
    }
}
