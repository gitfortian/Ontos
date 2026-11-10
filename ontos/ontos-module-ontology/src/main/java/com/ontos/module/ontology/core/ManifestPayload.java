package com.ontos.module.ontology.core;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import tools.jackson.databind.JsonNode;

/**
 * 发布清单 payload 的结构（v2）。payload 原文参与 content_hash 计算，解析结果只用于运行时读取。
 *
 * <p>v2 相对 v1 新增段（均为可选列表，版本兼容按 v1.4 §6.5——新增可选字段为小版本）：
 * rule（判定标准）、copyTemplate（对外文案）、timeExpressionPattern（相对时间模式，M2 解析器消费）、
 * scenario（业务场景元数据，M6 路由，M1 只校验引用完整性）。
 */
@Data
public class ManifestPayload {

    private SemanticManifest semantic;
    private OntologySnapshot ontology;
    private List<Rule> rules = List.of();
    private List<CopyTemplate> copyTemplates = List.of();
    private List<TimeExpressionPattern> timeExpressionPatterns = List.of();
    private List<ScenarioMeta> scenarios = List.of();
    /** Skill 资产（v1.4 §5.8，M6/T3.1）：能力单元注册表，运行时 SkillRegistry 按清单扩展段消费。 */
    private List<SkillMeta> skills = List.of();
    /** VQR 资产（v1.4 §11，M6/T3.4）：参数化查询模板——只存模板与资产引用，不存可执行 SQL。 */
    private List<VqrMeta> vqrs = List.of();

    /** 判定标准（ADR-0004 2.3）：随清单版本化，运行时只认 published。 */
    @Data
    public static class Rule {
        private String id;
        private String name;
        private String summary;
        /** domain | tenant | global */
        private String scope;
        /** 结构化表达式：{metricId, operator, threshold}，禁止内联脚本。 */
        private Map<String, Object> expression;
        /** draft | reviewing | published | retired */
        private String status;
        private String reviewer;
    }

    /** 对外文案（ADR-0004 2.5）：错误码/原因码只映射 templateId，不拼字符串。 */
    @Data
    public static class CopyTemplate {
        private String templateId;
        /**
         * 原因码（M3）。历史清单（v7 及以前）没有该字段，运行时按 templateId 的
         * `template.reason.<CODE>` 前缀派生，保证旧清单可继续加载。
         */
        private String reasonCode;
        private String locale;
        private String text;
        /** 允许出现在 text 中的占位符名；未声明的 `{x}` 一律拒绝发布。 */
        private List<String> allowedPlaceholders = List.of();
        private String version;

        /**
         * 生效原因码：显式字段优先；历史清单（v7 及以前）没有该字段，
         * 按 templateId 的 `template.reason.<CODE>` 前缀派生，保证旧清单继续可加载。
         */
        public String effectiveReasonCode() {
            if (reasonCode != null && !reasonCode.isBlank()) {
                return reasonCode;
            }
            String prefix = "template.reason.";
            if (templateId != null && templateId.startsWith(prefix)) {
                return templateId.substring(prefix.length());
            }
            return null;
        }
    }

    /** 相对时间表达模式（v1.4 §5.12），M1 只冻结结构，解析器归 M2。 */
    @Data
    public static class TimeExpressionPattern {
        private String patternId;
        private String pattern;
        private String granularity;
        private String offsetUnit;
        private Integer offsetAmount;
    }

    /** 业务场景资产（v1.4 §5.7）：M6 完整模型；M1 的扁平数组形态仍可加载。 */
    @Data
    public static class ScenarioMeta {
        private String id;
        private String name;
        private String summary;
        /** manual_only | recommend_or_confirm | auto_if_confident */
        private String entryMode;
        private List<String> defaultVqrIds = List.of();
        private String owner;
        private String version;
        /** draft | reviewing | published | retired */
        private String status;
        private String domainId;
        private List<String> policyRefs = List.of();
        /** 旧清单（v12 及以前）为概念 ID 数组；v13 起为完整对象。 */
        private OntologyScope ontologyScope = new OntologyScope();
        /** 旧清单（v12 及以前）为资产 ID 数组；v13 起为完整对象。 */
        private SemanticScope semanticScope = new SemanticScope();
        private List<String> allowedAnalysisModes = List.of();
        private List<SkillBinding> skills = List.of();
        private List<String> entryHints = List.of();
        /** 必须是已发布 VQR / Golden Query 的引用，不得是自由文本。 */
        private List<String> coldStartSamples = List.of();
        private VersionCompatibility versionCompatibility;
        private Invalidation invalidation;

        /** 旧扁平形态（无 owner/version/status）：只做最小校验，保证历史清单可加载。 */
        public boolean isLegacyShape() {
            return ontologyScope != null && ontologyScope.isLegacyShape();
        }
    }

    /** 本体 scope：根对象、允许关系、最大路径跳数、排除对象。 */
    @Data
    public static class OntologyScope {
        private List<String> rootConcepts = List.of();
        private List<String> allowedRelations = List.of();
        private Integer maxPathHops;
        private List<String> excludedConcepts = List.of();
        @JsonIgnore
        private boolean legacyShape;

        /** 兼容 v12 及以前的 `ontologyScope: [concept...]`。 */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static OntologyScope from(JsonNode node) {
            OntologyScope scope = new OntologyScope();
            if (node == null || node.isNull()) {
                return scope;
            }
            if (node.isArray()) {
                scope.setRootConcepts(texts(node));
                scope.setLegacyShape(true);
                return scope;
            }
            if (!node.isObject()) {
                throw new IllegalArgumentException("ontologyScope 必须是对象或概念数组");
            }
            scope.setRootConcepts(texts(node.get("rootConcepts")));
            scope.setAllowedRelations(texts(node.get("allowedRelations")));
            scope.setExcludedConcepts(texts(node.get("excludedConcepts")));
            if (node.hasNonNull("maxPathHops")) {
                scope.setMaxPathHops(node.get("maxPathHops").asInt());
            }
            return scope;
        }
    }

    /** 语义 scope：指标/维度白名单与冻结口径。 */
    @Data
    public static class SemanticScope {
        private List<String> metrics = List.of();
        private List<String> dimensions = List.of();
        private Map<String, FrozenCaliber> frozenCalibers = Map.of();
        @JsonIgnore
        private boolean legacyShape;

        /** 兼容 v12 及以前的 `semanticScope: [asset...]`。 */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static SemanticScope from(JsonNode node) {
            SemanticScope scope = new SemanticScope();
            if (node == null || node.isNull()) {
                return scope;
            }
            if (node.isArray()) {
                scope.setMetrics(texts(node));
                scope.setLegacyShape(true);
                return scope;
            }
            if (!node.isObject()) {
                throw new IllegalArgumentException("semanticScope 必须是对象或资产数组");
            }
            scope.setMetrics(texts(node.get("metrics")));
            scope.setDimensions(texts(node.get("dimensions")));
            JsonNode calibers = node.get("frozenCalibers");
            if (calibers != null && calibers.isObject()) {
                Map<String, FrozenCaliber> map = new java.util.LinkedHashMap<>();
                calibers.properties().forEach(entry -> {
                    JsonNode value = entry.getValue();
                    FrozenCaliber caliber = new FrozenCaliber();
                    caliber.setType(value.path("type").asString(null));
                    caliber.setValue(value.path("value").asString(null));
                    map.put(entry.getKey(), caliber);
                });
                scope.setFrozenCalibers(map);
            }
            return scope;
        }
    }

    /** 冻结口径：带类型的只读约束（v1.4 §5.7）。 */
    @Data
    public static class FrozenCaliber {
        /** string | metric_id | field_id */
        private String type;
        private String value;
    }

    /**
     * Skill 资产（v1.4 §5.8，M6/W3/T3.1）：能力单元，不是用户脚本容器。
     * implementation_ref 只指向应用内审核过的构造器（builtin.<mode>.<impl>@<semver>，
     * 命名空间由注册中心统一分配，Scenario 作者不可自定义）；reviewer/reviewedAt 是
     * 人工审核记录（T3.1 特有，生命周期 published 必有审核人）。
     */
    @Data
    public static class SkillMeta {
        private String skillId;
        private String name;
        private String owner;
        /** SemVer。 */
        private String version;
        /** draft | reviewing | published | retired（与 rules.status 同词表）。 */
        private String status;
        /** QUERY | LOOKUP | LOOKUP_BATCH | YOY_MOM | FUNNEL | RETENTION | ATTRIBUTION（大写枚举，同 skill_definition 列）。 */
        private String analysisMode;
        /** 指向已发布且版本固定的参数 Schema（如 schema.lookup.v1）。 */
        private String paramsSchemaRef;
        private String implementationRef;
        /** 必须被部署的受控工具注册表全部满足（编译期校验归 T3.2）。 */
        private List<String> requiredCapabilities = List.of();
        /** 空 = 全局可用（自由问数可路由）；非空 = 只允许列出的 Scenario。 */
        private List<String> allowedScenarioIds = List.of();
        /** 批量参数上界（LOOKUP_BATCH 的 limit ≤ 此值）；null = 不限。 */
        private Integer maxBatchSize;
        /** 预声明近似（§3.3）：本 Skill 是被引用 Skill 所在模式的近似承担者；跨模式引用。 */
        private String approximationOf;
        /** 人工审核记录：published 必有 reviewer。 */
        private String reviewer;
        private String reviewedAt;
    }

    /**
     * VQR 资产（v1.4 §11，M6/W3/T3.4）：参数化查询模板，不保存绕过权限的最终 SQL。
     * dslTemplate 只引用资产 ID + {@code ${param.x}} 占位符；goldenRef / resultCheckRef
     * 是入库七条件（§11.2）中执行类证据的**引用**（ADR-0017 D4：证据由 catalog 之外流水线
     * 产生，catalog/清单校验只验引用存在与格式，不连库执行）。
     */
    @Data
    public static class VqrMeta {
        private String vqrId;
        private String name;
        private String summary;
        private String owner;
        /** SemVer。 */
        private String version;
        /** draft | reviewing | published | retired（与 skills.status 同词表）。 */
        private String status;
        /** 问题模式（T3.5 候选匹配用；free/场景话术与此模式匹配时进入候选）。 */
        private String questionPattern;
        /** 参数化 DSL 模板：资产 ID 引用 + {@code ${param.x}} 占位符（object）。 */
        private Map<String, Object> dslTemplate;
        /** 参数 Schema（object）：键 = 参数名，值 = 类型/约束声明。 */
        private Map<String, Object> paramsSchema;
        /** Golden Query 证据引用（D4 外置：外部流水线产物 ID/路径）。 */
        private String goldenRef;
        /** 结果校验证据引用（D4 外置）。 */
        private String resultCheckRef;
        /** 数据新鲜度上限（小时，≥1）。 */
        private Integer freshnessLimitHours;
        /** 声明式失效条件（§11.3）：{assetId, trigger}；trigger ∈ deprecated | incompatible_change。 */
        private List<Map<String, Object>> invalidationConditions = List.of();
        /** 人工审核记录：published 必有 reviewer。 */
        private String reviewer;
        private String reviewedAt;
    }

    /** Scenario 绑定的已发布 Skill。 */
    @Data
    public static class SkillBinding {
        private String skillId;
        private String analysisMode;
        private String paramsSchemaRef;
    }

    /** 版本兼容约束：SemVer（^1.2.3）或日期前缀（2026.11 / 2026.11.*）。 */
    @Data
    public static class VersionCompatibility {
        private String ontology;
        private String semantic;
    }

    /** 失效策略：依赖资产废弃时阻断而非静默降级。 */
    @Data
    public static class Invalidation {
        /** block | warn */
        private String onScopeAssetDeprecation;
        private String onSkillDeprecation;
    }

    private static List<String> texts(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        node.forEach(item -> result.add(item.asString()));
        return List.copyOf(result);
    }
}
