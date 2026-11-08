package com.ontos.module.query.core.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.PATH_AMBIGUOUS;
import static com.ontos.module.query.enums.ErrorCodeConstants.PATH_GRANULARITY_VIOLATION;
import static com.ontos.module.query.enums.ErrorCodeConstants.PATH_UNREACHABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W3/T3.1：UNIQUE 路径展开、歧义/不可达拒绝、粒度扩散拒绝。 */
class JoinPathResolverTest {

    private static final String CUSTOMER_TABLE = "public.dim_customer";
    private static final String ORDER_TABLE = TestFixtures.TABLE;
    private static final Set<String> DOMAINS = Set.of("trade", "customer");

    private static final JoinPathResolver RESOLVER = new JoinPathResolver(() -> DOMAINS);

    private static OntologySnapshot.Concept concept(String id, String domain) {
        OntologySnapshot.Concept concept = new OntologySnapshot.Concept();
        concept.setId(id);
        concept.setDomain(domain);
        return concept;
    }

    private static OntologySnapshot.Property property(String id, String conceptId, String column) {
        OntologySnapshot.Property property = new OntologySnapshot.Property();
        property.setId(id);
        property.setConceptId(conceptId);
        property.setColumn(column);
        return property;
    }

    private static OntologySnapshot.Relation relation(String id, String cardinality, boolean queryable,
                                                       boolean golden) {
        OntologySnapshot.Relation relation = new OntologySnapshot.Relation();
        relation.setId(id);
        relation.setFrom("concept.customer");
        relation.setTo("concept.order");
        relation.setCardinality(cardinality);
        relation.setFromColumn(CUSTOMER_TABLE + ".id");
        relation.setToColumn(ORDER_TABLE + ".customer_id");
        relation.setQueryable(queryable);
        relation.setGoldenPath(golden);
        return relation;
    }

    private static OntologySnapshot ontology(List<OntologySnapshot.Relation> relations, boolean withProperties) {
        OntologySnapshot ontology = new OntologySnapshot();
        ontology.setConcepts(List.of(concept("concept.customer", "customer"), concept("concept.order", "trade")));
        ontology.setProperties(withProperties ? List.of(
                property("property.customer.id", "concept.customer", CUSTOMER_TABLE + ".id"),
                property("property.customer.tier", "concept.customer", CUSTOMER_TABLE + ".tier"),
                property("property.order.amount", "concept.order", ORDER_TABLE + ".amount"),
                property("property.order.customer_id", "concept.order", ORDER_TABLE + ".customer_id"))
                : List.of());
        ontology.setRelations(new ArrayList<>(relations));
        return ontology;
    }

    private static PublishedManifest manifest(OntologySnapshot ontology) {
        PublishedManifest manifest = TestFixtures.manifest();
        manifest.setOntology(ontology);
        return manifest;
    }

    private static QueryDsl crossTableDsl() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setDimensions(List.of("customer_tier"));
        return dsl;
    }

    @Test
    void shouldExpandUniquePathIntoJoin() {
        OntologySnapshot ontology = ontology(List.of(relation("r.placesOrder", "1:N", true, true)), true);
        QueryDsl dsl = crossTableDsl();

        RESOLVER.resolve(dsl, manifest(ontology));

        assertEquals(1, dsl.getResolvedJoins().size());
        QueryDsl.ResolvedJoin join = dsl.getResolvedJoins().get(0);
        assertEquals("r.placesOrder", join.getRelationId());
        assertEquals("reverse", join.getDirection());
        assertEquals(CUSTOMER_TABLE, join.getTable());
        assertEquals(CUSTOMER_TABLE + ".id", join.getLeftColumn());
        assertEquals(ORDER_TABLE + ".customer_id", join.getRightColumn());
        assertEquals("N:1", join.getCardinality());
    }

    @Test
    void shouldNotPlanJoinForSameTableDimensions() {
        OntologySnapshot ontology = ontology(List.of(relation("r.placesOrder", "1:N", true, true)), true);
        QueryDsl dsl = TestFixtures.query();

        RESOLVER.resolve(dsl, manifest(ontology));

        assertTrue(dsl.getResolvedJoins().isEmpty());
    }

    @Test
    void shouldRejectAmbiguousPaths() {
        OntologySnapshot ontology = ontology(List.of(
                relation("r.placesOrder", "1:N", true, true),
                relation("r.billingOrder", "1:N", true, true)), true);
        QueryDsl dsl = crossTableDsl();

        ServiceException ex = assertThrows(ServiceException.class,
                () -> RESOLVER.resolve(dsl, manifest(ontology)));
        assertEquals(PATH_AMBIGUOUS.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectUnreachablePath() {
        OntologySnapshot ontology = ontology(List.of(relation("r.placesOrder", "1:N", false, true)), true);
        QueryDsl dsl = crossTableDsl();

        ServiceException ex = assertThrows(ServiceException.class,
                () -> RESOLVER.resolve(dsl, manifest(ontology)));
        assertEquals(PATH_UNREACHABLE.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectWhenTableCannotBeMappedToConcept() {
        OntologySnapshot ontology = ontology(List.of(relation("r.placesOrder", "1:N", true, true)), false);
        QueryDsl dsl = crossTableDsl();

        ServiceException ex = assertThrows(ServiceException.class,
                () -> RESOLVER.resolve(dsl, manifest(ontology)));
        assertEquals(PATH_UNREACHABLE.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectGranularityExpansion() {
        OntologySnapshot ontology = ontology(List.of(relation("r.placesOrder", "1:N", true, true)), true);
        PublishedManifest manifest = manifest(ontology);
        // 指标落在 1 端（customer），维度落在 N 端（order）→ forward 1:N 粒度扩散
        manifest.getSemantic().getMetrics().get(0).setTable(CUSTOMER_TABLE);
        QueryDsl dsl = TestFixtures.query();

        ServiceException ex = assertThrows(ServiceException.class,
                () -> RESOLVER.resolve(dsl, manifest));
        assertEquals(PATH_GRANULARITY_VIOLATION.getCode(), ex.getCode());
    }

}
