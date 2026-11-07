package com.ontos.module.ontology.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * 本体发布校验：CI 阶段运行，失败即拒绝发布。
 * 只校验显式声明的结构与基数，不做隐式业务规则推导。
 */
@Slf4j
public class OntologyPublishValidator {

    private static final Pattern CARDINALITY = Pattern.compile("^(1:1|1:N|N:1|N:N)$");

    public List<Violation> validate(OntologySnapshot snapshot) {
        List<Violation> violations = new ArrayList<>();
        Set<String> conceptIds = new HashSet<>();
        Set<String> relationIds = new HashSet<>();

        for (OntologySnapshot.Concept concept : snapshot.getConcepts()) {
            if (concept.getId() == null || concept.getId().isBlank()) {
                violations.add(new Violation("CONCEPT_ID_EMPTY", "概念 ID 不能为空"));
                continue;
            }
            if (!conceptIds.add(concept.getId())) {
                violations.add(new Violation("CONCEPT_DUPLICATED", "概念 ID 重复: " + concept.getId()));
            }
        }

        for (OntologySnapshot.Relation relation : snapshot.getRelations()) {
            if (relation.getId() == null || relation.getId().isBlank()) {
                violations.add(new Violation("RELATION_ID_EMPTY", "关系 ID 不能为空"));
                continue;
            }
            if (!relationIds.add(relation.getId())) {
                violations.add(new Violation("RELATION_DUPLICATED", "关系 ID 重复: " + relation.getId()));
            }
            if (!conceptIds.contains(relation.getFrom())) {
                violations.add(new Violation("RELATION_FROM_UNKNOWN",
                        "关系 " + relation.getId() + " 的起点不存在: " + relation.getFrom()));
            }
            if (!conceptIds.contains(relation.getTo())) {
                violations.add(new Violation("RELATION_TO_UNKNOWN",
                        "关系 " + relation.getId() + " 的终点不存在: " + relation.getTo()));
            }
            if (relation.getCardinality() == null || !CARDINALITY.matcher(relation.getCardinality()).matches()) {
                violations.add(new Violation("RELATION_CARDINALITY_INVALID",
                        "关系 " + relation.getId() + " 的基数非法: " + relation.getCardinality()));
            }
        }
        return List.copyOf(violations);
    }

    public void validateOrThrow(OntologySnapshot snapshot) {
        List<Violation> violations = validate(snapshot);
        if (!violations.isEmpty()) {
            log.error("[validateOrThrow][violations({})]", violations);
            throw new IllegalStateException("本体发布校验失败: " + violations);
        }
    }

    public record Violation(String code, String message) {
    }
}
