package com.ontos.module.agent.service.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.springframework.stereotype.Component;

/**
 * 复杂模式自由问数参数补全（ADR-0023 v1.1 + ask 参数补全规格 v0.1）。
 *
 * <p>模型只给语义槽位（analysis_mode / metric_id / dimensions / filters / time_expression_raw /
 * attributes / identities）；本组件在 DSL 校验前补齐事件锚点、观察窗粒度、主体标识等
 * 确定性参数。模型不输出 analysis_params、事件 ID、Skill 内部参数或物理列。
 *
 * <p>参数不足时不猜测：返回 unresolved，由编排层转澄清或结构化拒答。
 */
@Component
public class ComplexAnalysisParamsResolver {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z]+-\\d+");
    private static final Pattern NEAR_DAYS = Pattern.compile("近\\s*(\\d+)\\s*天");
    private static final Pattern NEAR_MONTHS = Pattern.compile("近\\s*(\\d+)\\s*个?月");

    /** 补全结果：resolved=false 时 reason 是面向编排层的稳定原因，不含物理结构。 */
    public record Resolution(boolean resolved, QueryDsl dsl, String reason) {
        static Resolution ok(QueryDsl dsl) {
            return new Resolution(true, dsl, null);
        }

        static Resolution fail(String reason) {
            return new Resolution(false, null, reason);
        }
    }

    public Resolution resolve(QueryDsl dsl, String utterance, PublishedManifest manifest) {
        if (dsl == null || dsl.getMode() == null || manifest == null
                || manifest.getSemantic() == null || manifest.getOntology() == null) {
            return Resolution.fail("COMPLEX_PARAMS_CONTEXT_INVALID");
        }
        String text = utterance == null ? "" : utterance;
        return switch (dsl.getMode()) {
            case FUNNEL -> resolveFunnel(dsl, text, manifest);
            case RETENTION -> resolveRetention(dsl, text, manifest);
            case ATTRIBUTION -> resolveAttribution(dsl, text, manifest);
            case LOOKUP_BATCH -> resolveLookupBatch(dsl, text, manifest);
            case QUERY, LOOKUP, YOY_MOM, DISCOVERY -> Resolution.ok(dsl);
        };
    }

    private Resolution resolveFunnel(QueryDsl dsl, String utterance, PublishedManifest manifest) {
        List<EventHit> hits = eventHits(utterance, manifest);
        if (hits.size() < 2) {
            return Resolution.fail("FUNNEL_STEPS_MISSING");
        }
        QueryDsl.AnalysisParams params = dsl.getAnalysisParams() == null
                ? new QueryDsl.AnalysisParams() : dsl.getAnalysisParams();
        params.setSteps(List.of(hits.get(0).event().getEventType(), hits.get(1).event().getEventType()));
        String subject = subjectPropertyOf(hits.get(0).event(), manifest);
        if (subject == null) {
            return Resolution.fail("FUNNEL_SUBJECT_MISSING");
        }
        params.setSubjectPropertyId(subject);
        params.setStepWindow("7d");
        params.setDeduplication("first_occurrence");
        params.setConversionDenominator("previous_step");
        params.setCohortWindow(cohortWindow(timeExpression(dsl.getTimeExpressionRaw(), utterance,
                "REFUND".equals(hits.get(0).event().getEventType()) ? "近 180 天" : "近 90 天")));
        dsl.setAnalysisParams(params);
        // FUNNEL 的时间窗只走 cohortWindow（M2 冻结口径）；顶层 raw 会触发校验拒绝。
        dsl.setTimeExpressionRaw(null);
        return Resolution.ok(dsl);
    }

    private Resolution resolveRetention(QueryDsl dsl, String utterance, PublishedManifest manifest) {
        String raw = timeExpression(dsl.getTimeExpressionRaw(), utterance, null);
        if (raw == null) {
            return Resolution.fail("RETENTION_TIME_MISSING");
        }
        Integer days = daysOf(raw);
        if (days == null) {
            return Resolution.fail("RETENTION_TIME_UNSUPPORTED");
        }
        OntologySnapshot.EventDef cohortEvent = eventByType("ORDER_PLACED", manifest);
        if (cohortEvent == null) {
            return Resolution.fail("RETENTION_EVENT_MISSING");
        }
        String subject = subjectPropertyOf(cohortEvent, manifest);
        if (subject == null) {
            return Resolution.fail("RETENTION_SUBJECT_MISSING");
        }
        String unit = utterance.contains("周") ? "WEEK"
                : utterance.contains("月") ? "MONTH"
                : days <= 31 ? "WEEK" : "MONTH";
        int periods = unit.equals("WEEK")
                ? Math.max(2, Math.min(4, days / 7))
                : Math.max(2, Math.min(12, days / 30));
        QueryDsl.AnalysisParams params = dsl.getAnalysisParams() == null
                ? new QueryDsl.AnalysisParams() : dsl.getAnalysisParams();
        params.setCohortEvent(cohortEvent.getEventType());
        params.setReturnEvent(cohortEvent.getEventType());
        params.setPeriodUnit(unit);
        params.setPeriods(periods);
        params.setSubjectPropertyId(subject);
        dsl.setAnalysisParams(params);
        dsl.setTimeExpressionRaw(raw);
        return Resolution.ok(dsl);
    }

    private Resolution resolveAttribution(QueryDsl dsl, String utterance, PublishedManifest manifest) {
        if (dsl.getMetricId() == null || dsl.getMetricId().isBlank()) {
            return Resolution.fail("ATTRIBUTION_METRIC_MISSING");
        }
        SemanticManifest.Metric metric = manifest.getSemantic().getMetrics() == null ? null
                : manifest.getSemantic().getMetrics().stream()
                        .filter(item -> dsl.getMetricId().equals(item.getId()))
                        .findFirst().orElse(null);
        if (metric == null || !metric.getAllowedModes().contains("ATTRIBUTION")) {
            return Resolution.fail("ATTRIBUTION_METRIC_UNSUPPORTED");
        }
        OntologySnapshot.EventDef conversion = eventByType("ORDER_PLACED", manifest);
        OntologySnapshot.EventDef touch = eventByType("CHANNEL_TOUCH", manifest);
        if (conversion == null || touch == null || touch.getChannelPropertyId() == null) {
            return Resolution.fail("ATTRIBUTION_EVENT_MISSING");
        }
        String subject = subjectPropertyOf(conversion, manifest);
        if (subject == null) {
            return Resolution.fail("ATTRIBUTION_SUBJECT_MISSING");
        }
        QueryDsl.AnalysisParams params = dsl.getAnalysisParams() == null
                ? new QueryDsl.AnalysisParams() : dsl.getAnalysisParams();
        params.setConversionEvent(conversion.getEventType());
        params.setTouchEvent(touch.getEventType());
        params.setAttributionWindow("30d");
        params.setModel("last_touch");
        params.setSubjectPropertyId(subject);
        dsl.setAnalysisParams(params);
        dsl.setTimeExpressionRaw(timeExpression(dsl.getTimeExpressionRaw(), utterance, null));
        return Resolution.ok(dsl);
    }

    private Resolution resolveLookupBatch(QueryDsl dsl, String utterance, PublishedManifest manifest) {
        List<String> identities = strings(dsl.getIdentities());
        if (identities.isEmpty()) {
            identities = idsIn(utterance);
        }
        if (identities.isEmpty()) {
            return Resolution.fail("LOOKUP_BATCH_IDENTITIES_MISSING");
        }
        String attributeId = strings(dsl.getAttributes()).stream().findFirst().orElse(null);
        OntologySnapshot.Property property = attributeId == null ? null
                : manifest.getOntology().propertyIndex().get(attributeId);
        if (property == null) {
            return Resolution.fail("LOOKUP_BATCH_ATTRIBUTE_MISSING");
        }
        OntologySnapshot.Concept concept = manifest.getOntology().conceptIndex().get(property.getConceptId());
        if (concept == null || concept.getIdentityProperty() == null || concept.getIdentityProperty().isBlank()) {
            return Resolution.fail("LOOKUP_BATCH_IDENTITY_PROPERTY_MISSING");
        }
        dsl.setEntityId(concept.getId());
        dsl.setIdentityPropertyId(concept.getIdentityProperty());
        dsl.setIdentities(new ArrayList<>(identities));
        return Resolution.ok(dsl);
    }

    private record EventHit(OntologySnapshot.EventDef event, int position) {
    }

    /** 从事件名 / 别名在话术中的首次位置推导有序步骤；找不到的事件不猜。 */
    private List<EventHit> eventHits(String utterance, PublishedManifest manifest) {
        String text = utterance == null ? "" : utterance.toLowerCase();
        Map<String, EventHit> hits = new LinkedHashMap<>();
        if (manifest.getOntology().getEvents() != null) {
            for (OntologySnapshot.EventDef event : manifest.getOntology().getEvents()) {
                int position = Integer.MAX_VALUE;
                for (String term : eventTerms(event)) {
                    int index = text.indexOf(term.toLowerCase());
                    if (index >= 0) {
                        position = Math.min(position, index);
                    }
                }
                if (position != Integer.MAX_VALUE) {
                    hits.put(event.getEventType(), new EventHit(event, position));
                }
            }
        }
        return hits.values().stream()
                .sorted((left, right) -> {
                    int byPosition = Integer.compare(left.position(), right.position());
                    return byPosition != 0 ? byPosition
                            : left.event().getEventType().compareTo(right.event().getEventType());
                })
                .toList();
    }

    private List<String> eventTerms(OntologySnapshot.EventDef event) {
        Set<String> terms = new LinkedHashSet<>();
        if (event.getName() != null && !event.getName().isBlank()) {
            terms.add(event.getName().replace("事件", "").trim());
        }
        if (event.getAliases() != null) {
            terms.addAll(event.getAliases());
        }
        terms.removeIf(String::isBlank);
        return List.copyOf(terms);
    }

    private OntologySnapshot.EventDef eventByType(String eventType, PublishedManifest manifest) {
        return manifest.getOntology().getEvents() == null ? null
                : manifest.getOntology().getEvents().stream()
                        .filter(event -> eventType.equals(event.getEventType()))
                        .findFirst().orElse(null);
    }

    private String subjectPropertyOf(OntologySnapshot.EventDef event, PublishedManifest manifest) {
        OntologySnapshot.Concept concept = manifest.getOntology().conceptIndex().get(event.getParticipantClass());
        return concept == null || concept.getIdentityProperty() == null || concept.getIdentityProperty().isBlank()
                ? null : concept.getIdentityProperty();
    }

    /** 优先用 DSL 槽位；缺失时从话术归一化。输出必须匹配清单时间模式（含空格）。 */
    private String timeExpression(String raw, String utterance, String fallback) {
        String source = raw != null && !raw.isBlank() ? raw : utterance;
        if (source == null || source.isBlank()) {
            return fallback;
        }
        String text = source.replaceAll("\\s+", "");
        if (text.contains("近半年")) {
            return "近 180 天";
        }
        Matcher days = NEAR_DAYS.matcher(text);
        if (days.matches()) {
            return "近 " + Integer.parseInt(days.group(1)) + " 天";
        }
        if (text.equals("今年") || text.equals("上季度") || text.equals("上个月") || text.equals("这个月")) {
            return text;
        }
        return raw != null && !raw.isBlank() ? raw.trim() : fallback;
    }

    private QueryDsl.CohortWindow cohortWindow(String raw) {
        QueryDsl.CohortWindow window = new QueryDsl.CohortWindow();
        window.setTimeExpressionRaw(raw);
        return window;
    }

    private Integer daysOf(String raw) {
        String text = raw.replaceAll("\\s+", "");
        if (text.contains("近半年")) {
            return 180;
        }
        Matcher days = NEAR_DAYS.matcher(text);
        if (days.matches()) {
            return Integer.parseInt(days.group(1));
        }
        Matcher months = NEAR_MONTHS.matcher(text);
        if (months.matches()) {
            return Integer.parseInt(months.group(1)) * 30;
        }
        return switch (text) {
            case "今年" -> 365;
            case "上季度" -> 90;
            case "这个月", "上个月" -> 30;
            default -> null;
        };
    }

    private static List<String> strings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

    private static List<String> idsIn(String utterance) {
        if (utterance == null || utterance.isBlank()) {
            return List.of();
        }
        return ID_PATTERN.matcher(utterance).results()
                .map(matchResult -> matchResult.group())
                .toList();
    }
}
