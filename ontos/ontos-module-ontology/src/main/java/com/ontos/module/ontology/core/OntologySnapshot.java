package com.ontos.module.ontology.core;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.Data;

/**
 * 本体快照：概念、关系、基数与约束的版本化定义。
 * 推理只做显式有限跳数遍历，不做隐式业务规则推导。
 */
@Data
public class OntologySnapshot {

    private String ontologyVersion;
    private List<Concept> concepts = List.of();
    private List<Relation> relations = List.of();

    public Map<String, Concept> conceptIndex() {
        return concepts.stream().collect(Collectors.toUnmodifiableMap(Concept::getId, concept -> concept));
    }

    @Data
    public static class Concept {
        private String id;
        private String name;
        private List<String> aliases = List.of();
    }

    /**
     * @param cardinality 形如 1:1 / 1:N / N:1 / N:N，编译器据此校验粒度
     */
    @Data
    public static class Relation {
        private String id;
        private String name;
        private String from;
        private String to;
        private String cardinality;
    }
}
