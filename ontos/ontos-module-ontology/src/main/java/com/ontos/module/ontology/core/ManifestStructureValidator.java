package com.ontos.module.ontology.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 清单 payload v2 结构校验：业务化必填字段、物理名格式（ADR-0007）、引用完整性。
 *
 * <p>与 {@link OntologyPublishValidator}（本体图语义校验）互补：本类管「结构与字段」，
 * 后者管「图语义」。发布预检与运行时装配（assemble）都调用，任何违规拒绝发布/加载。
 *
 * <p>业务化必填字段（docs/04-specs/ontology-业务化命名-规格-v1.md）：每条资产必须
 * name + summary + aliases(≥1)；资产描述不得携带物理名——物理名只允许出现在映射字段。
 */
public final class ManifestStructureValidator {

    public record Violation(String location, String message) {
        @Override
        public String toString() {
            return location + ": " + message;
        }
    }

    /** ADR-0007：物理名必须小写 snake_case 的限定名（schema.table.column）。 */
    private static final Pattern PHYSICAL_NAME = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");
    private static final Set<String> CARDINALITIES = Set.of("1:1", "1:N", "N:1", "N:M");
    private static final Set<String> RULE_SCOPES = Set.of("domain", "tenant", "global");
    private static final Set<String> RULE_STATUSES = Set.of("draft", "reviewing", "published", "retired");
    private static final Set<String> ENTRY_MODES = Set.of("manual_only", "recommend_or_confirm", "auto_if_confident");
    /** 判定表达式允许的比较算子（封闭枚举，禁止内联脚本）。 */
    private static final Set<String> EXPRESSION_OPERATORS = Set.of("EQ", "NEQ", "GT", "GTE", "LT", "LTE", "IN");
    /** 信息项值类型封闭词表。 */
    private static final Set<String> VALUE_TYPES = Set.of("string", "integer", "decimal", "boolean", "date", "timestamp");
    /** 分析模式封闭枚举（与编译器 AnalysisMode 对应，小写形态）。 */
    private static final Set<String> ANALYSIS_MODES = Set.of(
            "query", "lookup", "lookup_batch", "funnel", "yoy_mom", "attribution", "retention", "discovery");

    public List<Violation> validate(ManifestPayload payload) {
        List<Violation> violations = new ArrayList<>();
        if (payload == null) {
            violations.add(new Violation("payload", "payload 缺失"));
            return violations;
        }
        Set<String> publishedRuleIds = orEmpty(payload.getRules()).stream()
                .filter(rule -> "published".equals(rule.getStatus()))
                .map(ManifestPayload.Rule::getId)
                .collect(java.util.stream.Collectors.toSet());
        validateSemantic(payload.getSemantic(), publishedRuleIds, violations);
        validateOntology(payload.getOntology(), violations);
        validateRules(payload, violations);
        validateScenarios(payload, violations);
        validateCopyTemplates(payload, violations);
        validateTimePatterns(payload, violations);
        return violations;
    }

    private void validateSemantic(SemanticManifest semantic, Set<String> publishedRuleIds,
                                  List<Violation> violations) {
        if (semantic == null) {
            violations.add(new Violation("semantic", "缺少 semantic 段"));
            return;
        }
        if (blank(semantic.getSemanticVersion())) {
            violations.add(new Violation("semantic", "缺少 semanticVersion"));
        }
        Set<String> metricIds = new HashSet<>();
        for (SemanticManifest.Metric metric : orEmpty(semantic.getMetrics())) {
            String at = "metric." + metric.getId();
            requireId(metric.getId(), at, violations);
            requireBusinessFields(metric.getName(), metric.getSummary(), metric.getAliases(), at, violations);
            if (blank(metric.getRuleId())) {
                // 常规指标：物理映射 + 聚合必填
                requirePhysicalName(metric.getTable(), at + ".table", violations);
                requirePhysicalName(metric.getColumn(), at + ".column", violations);
                if (metric.getAggregation() == null) {
                    violations.add(new Violation(at, "缺少 aggregation"));
                }
            } else {
                // 布尔判定指标：引用已发布 Rule，无物理列与聚合；允许的维度走 Rule 判定输出
                if (!publishedRuleIds.contains(metric.getRuleId())) {
                    violations.add(new Violation(at,
                            "ruleId 引用不存在或未发布的判定标准: " + metric.getRuleId()));
                }
            }
            if (orEmpty(metric.getAllowedModes()).isEmpty()) {
                violations.add(new Violation(at, "allowedModes 不能为空"));
            }
            metricIds.add(metric.getId());
        }
        if (metricIds.isEmpty()) {
            violations.add(new Violation("semantic", "清单必须至少包含一个指标"));
        }
        for (SemanticManifest.Dimension dimension : orEmpty(semantic.getDimensions())) {
            String at = "dimension." + dimension.getId();
            requireId(dimension.getId(), at, violations);
            requireBusinessFields(dimension.getName(), dimension.getSummary(), dimension.getAliases(), at, violations);
            requirePhysicalName(dimension.getTable(), at + ".table", violations);
            requirePhysicalName(dimension.getColumn(), at + ".column", violations);
        }
    }

    private void validateOntology(OntologySnapshot ontology, List<Violation> violations) {
        if (ontology == null) {
            violations.add(new Violation("ontology", "缺少 ontology 段"));
            return;
        }
        Set<String> conceptIds = new HashSet<>();
        Set<String> propertyIds = new HashSet<>();
        Map<String, OntologySnapshot.Property> propertyById = new java.util.HashMap<>();
        for (OntologySnapshot.Concept concept : orEmpty(ontology.getConcepts())) {
            String at = "concept." + concept.getId();
            requireId(concept.getId(), at, violations);
            requireBusinessFields(concept.getName(), concept.getSummary(), concept.getAliases(), at, violations);
            if (blank(concept.getIdentityProperty())) {
                violations.add(new Violation(at, "缺少主标识属性 identityProperty"));
            }
            if (blank(concept.getDomain())) {
                violations.add(new Violation(at, "缺少授权域 domain"));
            }
            conceptIds.add(concept.getId());
        }
        for (OntologySnapshot.Property property : orEmpty(ontology.getProperties())) {
            String at = "property." + property.getId();
            requireId(property.getId(), at, violations);
            requireBusinessFields(property.getName(), property.getSummary(), property.getAliases(), at, violations);
            if (!conceptIds.contains(property.getConceptId())) {
                violations.add(new Violation(at, "conceptId 引用不存在的业务对象: " + property.getConceptId()));
            }
            if (blank(property.getValueType())) {
                violations.add(new Violation(at, "缺少 valueType"));
            } else if (!VALUE_TYPES.contains(property.getValueType())) {
                violations.add(new Violation(at, "valueType 不在封闭词表内: " + property.getValueType()));
            }
            if (property.getSensitivity() == null) {
                violations.add(new Violation(at, "缺少 sensitivity"));
            }
            requirePhysicalName(property.getColumn(), at + ".column", violations);
            propertyIds.add(property.getId());
            propertyById.put(property.getId(), property);
        }
        for (OntologySnapshot.Concept concept : orEmpty(ontology.getConcepts())) {
            String identity = concept.getIdentityProperty();
            OntologySnapshot.Property property = identity == null ? null : ontology.propertyIndex().get(identity);
            if (property == null) {
                violations.add(new Violation("concept." + concept.getId(),
                        "identityProperty 引用不存在的信息项: " + identity));
            } else if (!concept.getId().equals(property.getConceptId())) {
                violations.add(new Violation("concept." + concept.getId(),
                        "identityProperty 不属于该业务对象: " + identity));
            }
        }
        Set<String> relationIds = new HashSet<>();
        for (OntologySnapshot.Relation relation : orEmpty(ontology.getRelations())) {
            String at = "relation." + relation.getId();
            requireId(relation.getId(), at, violations);
            requireBusinessFields(relation.getName(), relation.getSummary(), relation.getAliases(), at, violations);
            if (!conceptIds.contains(relation.getFrom())) {
                violations.add(new Violation(at, "from 引用不存在的业务对象: " + relation.getFrom()));
            }
            if (!conceptIds.contains(relation.getTo())) {
                violations.add(new Violation(at, "to 引用不存在的业务对象: " + relation.getTo()));
            }
            if (relation.getCardinality() == null || !CARDINALITIES.contains(relation.getCardinality())) {
                violations.add(new Violation(at, "cardinality 非法: " + relation.getCardinality()));
            }
            if (blank(relation.getReverseLabel())) {
                violations.add(new Violation(at, "缺少逆向动词 reverseLabel"));
            }
            requirePhysicalName(relation.getFromColumn(), at + ".fromColumn", violations);
            requirePhysicalName(relation.getToColumn(), at + ".toColumn", violations);
            relationIds.add(relation.getId());
        }
        for (OntologySnapshot.EventDef event : orEmpty(ontology.getEvents())) {
            String at = "event." + event.getId();
            requireId(event.getId(), at, violations);
            requireBusinessFields(event.getName(), event.getSummary(), event.getAliases(), at, violations);
            List<String> missing = new ArrayList<>();
            if (blank(event.getParticipantClass())) missing.add("participantClass");
            if (blank(event.getTimeProperty())) missing.add("timeProperty");
            if (blank(event.getEventType())) missing.add("eventType");
            if (blank(event.getDedupKey())) missing.add("dedupKey");
            if (!missing.isEmpty()) {
                violations.add(new Violation(at, "事件四要素缺失: " + String.join(", ", missing)));
            }
            // ADR-0012：事件判定映射为可选字段，但声明后必须可解析且与时间属性同表
            if (blank(event.getEventTypeProperty()) && !blank(event.getEventTypeValue())) {
                violations.add(new Violation(at, "声明了 eventTypeValue 但缺少 eventTypeProperty"));
            }
            List<String> mapped = new ArrayList<>();
            if (!blank(event.getTimeProperty())) {
                mapped.add(event.getTimeProperty());
            }
            if (!blank(event.getEventTypeProperty())) {
                mapped.add(event.getEventTypeProperty());
                OntologySnapshot.Property typeProperty = propertyById.get(event.getEventTypeProperty());
                if (typeProperty == null) {
                    violations.add(new Violation(at, "eventTypeProperty 引用不存在的属性: "
                            + event.getEventTypeProperty()));
                } else if (!typeProperty.isFilterable()) {
                    violations.add(new Violation(at, "eventTypeProperty 不可过滤: " + event.getEventTypeProperty()));
                }
            }
            if (!blank(event.getParticipantProperty())) {
                mapped.add(event.getParticipantProperty());
                if (!propertyById.containsKey(event.getParticipantProperty())) {
                    violations.add(new Violation(at, "participantProperty 引用不存在的属性: "
                            + event.getParticipantProperty()));
                }
            }
            String eventTable = null;
            for (String propertyId : mapped) {
                OntologySnapshot.Property property = propertyById.get(propertyId);
                if (property == null) {
                    continue; // 已在上面单独报错
                }
                String table = tableOf(property.getColumn());
                if (table == null) {
                    violations.add(new Violation(at, "事件映射属性缺少表限定列名: " + propertyId));
                } else if (eventTable == null) {
                    eventTable = table;
                } else if (!eventTable.equals(table)) {
                    violations.add(new Violation(at, "事件映射属性跨表: " + eventTable + " vs " + table));
                }
            }
        }
        for (OntologySnapshot.Synonym synonym : orEmpty(ontology.getSynonyms())) {
            if (blank(synonym.getTerm()) || blank(synonym.getAssetId())) {
                violations.add(new Violation("synonym", "term 与 assetId 均必填"));
            }
        }
    }

    private void validateRules(ManifestPayload payload, List<Violation> violations) {
        Set<String> metricIds = orEmpty(payload.getSemantic() == null ? null : payload.getSemantic().getMetrics())
                .stream().map(SemanticManifest.Metric::getId).collect(java.util.stream.Collectors.toSet());
        for (ManifestPayload.Rule rule : orEmpty(payload.getRules())) {
            String at = "rule." + rule.getId();
            requireId(rule.getId(), at, violations);
            // 判定标准/场景是配置资产，不是检索目标：只要求 name+summary，不要求 aliases
            if (blank(rule.getName())) {
                violations.add(new Violation(at, "业务名 name 必填"));
            }
            if (blank(rule.getSummary())) {
                violations.add(new Violation(at, "一句话说明 summary 必填"));
            }
            if (rule.getScope() == null || !RULE_SCOPES.contains(rule.getScope())) {
                violations.add(new Violation(at, "scope 非法: " + rule.getScope()));
            }
            if (rule.getStatus() == null || !RULE_STATUSES.contains(rule.getStatus())) {
                violations.add(new Violation(at, "status 非法: " + rule.getStatus()));
            }
            Map<String, Object> expression = rule.getExpression();
            if (expression == null || blank((String) expression.get("metricId"))) {
                violations.add(new Violation(at, "expression.metricId 必填（判定只能引用指标，禁止内联脚本）"));
            } else if (!metricIds.contains(expression.get("metricId"))) {
                violations.add(new Violation(at, "expression.metricId 引用不存在的指标: " + expression.get("metricId")));
            }
            Object operator = expression == null ? null : expression.get("operator");
            if (operator == null || !EXPRESSION_OPERATORS.contains(String.valueOf(operator))) {
                violations.add(new Violation(at, "expression.operator 不在封闭枚举内: " + operator));
            }
            if (expression == null || !expression.containsKey("threshold")) {
                violations.add(new Violation(at, "expression.threshold 必填"));
            }
        }
    }

    private void validateScenarios(ManifestPayload payload, List<Violation> violations) {
        Set<String> conceptIds = payload.getOntology() == null ? Set.of()
                : payload.getOntology().getConcepts().stream()
                        .map(OntologySnapshot.Concept::getId)
                        .collect(java.util.stream.Collectors.toSet());
        Set<String> assetIds = new HashSet<>();
        if (payload.getSemantic() != null) {
            orEmpty(payload.getSemantic().getMetrics()).forEach(m -> assetIds.add(m.getId()));
            orEmpty(payload.getSemantic().getDimensions()).forEach(d -> assetIds.add(d.getId()));
        }
        for (ManifestPayload.ScenarioMeta scenario : orEmpty(payload.getScenarios())) {
            String at = "scenario." + scenario.getId();
            requireId(scenario.getId(), at, violations);
            if (blank(scenario.getName())) {
                violations.add(new Violation(at, "业务名 name 必填"));
            }
            if (blank(scenario.getSummary())) {
                violations.add(new Violation(at, "一句话说明 summary 必填"));
            }
            if (scenario.getEntryMode() == null || !ENTRY_MODES.contains(scenario.getEntryMode())) {
                violations.add(new Violation(at, "entryMode 非法: " + scenario.getEntryMode()));
            }
            for (String conceptId : orEmpty(scenario.getOntologyScope())) {
                if (!conceptIds.contains(conceptId)) {
                    violations.add(new Violation(at, "ontologyScope 引用不存在的业务对象: " + conceptId));
                }
            }
            for (String assetId : orEmpty(scenario.getSemanticScope())) {
                if (!assetIds.contains(assetId)) {
                    violations.add(new Violation(at, "semanticScope 引用不存在的资产: " + assetId));
                }
            }
            for (String mode : orEmpty(scenario.getAllowedAnalysisModes())) {
                if (!ANALYSIS_MODES.contains(mode)) {
                    violations.add(new Violation(at, "allowedAnalysisModes 不在封闭枚举内: " + mode));
                }
            }
        }
    }

    private void validateCopyTemplates(ManifestPayload payload, List<Violation> violations) {
        for (ManifestPayload.CopyTemplate template : orEmpty(payload.getCopyTemplates())) {
            String at = "copyTemplate." + template.getTemplateId();
            if (blank(template.getTemplateId()) || blank(template.getLocale()) || blank(template.getText())) {
                violations.add(new Violation(at, "templateId / locale / text 均必填"));
            }
        }
    }

    private void validateTimePatterns(ManifestPayload payload, List<Violation> violations) {
        for (ManifestPayload.TimeExpressionPattern pattern : orEmpty(payload.getTimeExpressionPatterns())) {
            String at = "timeExpressionPattern." + pattern.getPatternId();
            if (blank(pattern.getPatternId()) || blank(pattern.getPattern()) || blank(pattern.getGranularity())) {
                violations.add(new Violation(at, "patternId / pattern / granularity 均必填"));
            }
        }
    }

    private void requireId(String id, String at, List<Violation> violations) {
        if (blank(id)) {
            violations.add(new Violation(at, "id 必填"));
        }
    }

    private void requireBusinessFields(String name, String summary, List<String> aliases,
                                       String at, List<Violation> violations) {
        if (blank(name)) {
            violations.add(new Violation(at, "业务名 name 必填（业务化命名规格）"));
        }
        if (blank(summary)) {
            violations.add(new Violation(at, "一句话说明 summary 必填（业务化命名规格）"));
        }
        if (orEmpty(aliases).isEmpty()) {
            violations.add(new Violation(at, "常见叫法 aliases 至少 1 条（业务化命名规格，进检索词库）"));
        }
    }

    private void requirePhysicalName(String physicalName, String at, List<Violation> violations) {
        if (blank(physicalName) || !PHYSICAL_NAME.matcher(physicalName).matches()) {
            violations.add(new Violation(at,
                    "物理名必须是小写 snake_case 限定名（ADR-0007）: " + physicalName));
        }
    }

    /** "table.column" / "schema.table.column" → 表限定名；裸列名返回 null。 */
    private static String tableOf(String column) {
        if (column == null) {
            return null;
        }
        int lastDot = column.lastIndexOf('.');
        return lastDot <= 0 ? null : column.substring(0, lastDot);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

}
