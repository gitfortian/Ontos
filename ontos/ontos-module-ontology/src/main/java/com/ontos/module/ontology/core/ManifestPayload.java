package com.ontos.module.ontology.core;

import java.util.List;
import java.util.Map;

import lombok.Data;

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

    /** 业务场景元数据（v1.4 §5.7）：路由归 M6，M1 只承载与校验。 */
    @Data
    public static class ScenarioMeta {
        private String id;
        private String name;
        private String summary;
        /** manual_only | recommend_or_confirm | auto_if_confident */
        private String entryMode;
        private List<String> ontologyScope = List.of();
        private List<String> semanticScope = List.of();
        private List<String> allowedAnalysisModes = List.of();
    }
}
