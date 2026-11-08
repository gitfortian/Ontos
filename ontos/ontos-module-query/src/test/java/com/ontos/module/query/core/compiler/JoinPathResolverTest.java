package com.ontos.module.query.core.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
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
    private static final String ITEM_TABLE = "public.dim_item";
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

    @Test
    void shouldDeduplicateOverlappingPathsAcrossTargetTables() {
        // 深度审核回归：目标 1 = customer（路径 r1），目标 2 = item（路径 r1+r2）。
        // 旧实现会为第二条路径重复展开 r1，并把两端已引入的边退化成 WHERE 等值条件。
        OntologySnapshot ontology = new OntologySnapshot();
        ontology.setConcepts(List.of(
                concept("concept.customer", "customer"),
                concept("concept.order", "trade"),
                concept("concept.order_item", "trade")));
        ontology.setProperties(List.of(
                property("p.customer.id", "concept.customer", CUSTOMER_TABLE + ".id"),
                property("p.order.id", "concept.order", ORDER_TABLE + ".id"),
                property("p.item.id", "concept.order_item", ITEM_TABLE + ".id")));

        OntologySnapshot.Relation r1 = relation("r.placesOrder", "1:N", true, true);
        OntologySnapshot.Relation r2 = new OntologySnapshot.Relation();
        r2.setId("r.itemOfCustomer");
        r2.setFrom("concept.customer");
        r2.setTo("concept.order_item");
        r2.setCardinality("N:1");
        r2.setFromColumn(CUSTOMER_TABLE + ".id");
        r2.setToColumn(ITEM_TABLE + ".customer_id");
        r2.setQueryable(true);
        r2.setGoldenPath(false);
        ontology.setRelations(List.of(r1, r2));

        PublishedManifest manifest = manifest(ontology);
        SemanticManifest.Dimension item = new SemanticManifest.Dimension();
        item.setId("item_name");
        item.setTable(ITEM_TABLE);
        item.setColumn(ITEM_TABLE + ".name");
        List<SemanticManifest.Dimension> dimensions =
                new ArrayList<>(manifest.getSemantic().getDimensions());
        dimensions.add(item);
        manifest.getSemantic().setDimensions(dimensions);

        QueryDsl dsl = TestFixtures.query();
        dsl.setDimensions(List.of("customer_tier", "item_name"));

        RESOLVER.resolve(dsl, manifest);

        assertEquals(2, dsl.getResolvedJoins().size(), "重叠路径只展开两条边，不产生重复 JOIN");
        assertTrue(dsl.getResolvedJoins().stream().allMatch(join -> join.getTable() != null),
                "每条解析后的 Join 必须有待引入表，禁止静默退化为 WHERE");
        assertEquals(List.of("r.placesOrder", "r.itemOfCustomer"),
                dsl.getResolvedJoins().stream().map(QueryDsl.ResolvedJoin::getRelationId).toList());
    }

}
