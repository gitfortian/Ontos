package com.ontos.module.ontology.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyPublishValidatorTest {

    private final OntologyPublishValidator validator = new OntologyPublishValidator();

    @Test
    void shouldAcceptValidSnapshot() {
        assertTrue(validator.validate(snapshot()).isEmpty());
    }

    @Test
    void shouldRejectDuplicatedConceptId() {
        OntologySnapshot snapshot = snapshot();
        OntologySnapshot.Concept duplicate = new OntologySnapshot.Concept();
        duplicate.setId("order");
        snapshot.setConcepts(List.of(snapshot.getConcepts().get(0), duplicate));

        assertTrue(validator.validate(snapshot).stream()
                .anyMatch(v -> v.code().equals("CONCEPT_DUPLICATED")));
    }

    @Test
    void shouldRejectRelationWithUnknownEndpoint() {
        OntologySnapshot snapshot = snapshot();
        snapshot.getRelations().get(0).setTo("missing_concept");

        assertTrue(validator.validate(snapshot).stream()
                .anyMatch(v -> v.code().equals("RELATION_TO_UNKNOWN")));
    }

    @Test
    void shouldRejectInvalidCardinality() {
        OntologySnapshot snapshot = snapshot();
        snapshot.getRelations().get(0).setCardinality("1:M");

        assertTrue(validator.validate(snapshot).stream()
                .anyMatch(v -> v.code().equals("RELATION_CARDINALITY_INVALID")));
    }

    @Test
    void shouldReportAllViolationsAtOnce() {
        OntologySnapshot snapshot = snapshot();
        snapshot.getRelations().get(0).setCardinality(null);
        snapshot.getRelations().get(0).setFrom(null);
        snapshot.getConcepts().get(0).setId(" ");

        // 空概念 ID、未知起点、非法基数各一条
        assertEquals(3, validator.validate(snapshot).size());
    }

    @Test
    void shouldThrowOnViolations() {
        OntologySnapshot snapshot = snapshot();
        snapshot.getRelations().get(0).setCardinality("X:Y");
        assertThrows(IllegalStateException.class, () -> validator.validateOrThrow(snapshot));
    }

    private static OntologySnapshot snapshot() {
        OntologySnapshot.Concept order = new OntologySnapshot.Concept();
        order.setId("order");
        order.setName("订单");
        OntologySnapshot.Concept customer = new OntologySnapshot.Concept();
        customer.setId("customer");
        customer.setName("客户");

        OntologySnapshot.Relation relation = new OntologySnapshot.Relation();
        relation.setId("order_placed_by_customer");
        relation.setFrom("order");
        relation.setTo("customer");
        relation.setCardinality("N:1");

        OntologySnapshot snapshot = new OntologySnapshot();
        snapshot.setOntologyVersion("ont-1.0.0");
        snapshot.setConcepts(List.of(order, customer));
        snapshot.setRelations(List.of(relation));
        return snapshot;
    }
}
