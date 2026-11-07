package com.ontos.module.ontology.core;

import java.util.List;
import java.util.Set;

import com.ontos.module.ontology.core.OntologyPathPlanner.PlanResult;
import com.ontos.module.ontology.core.OntologyPathPlanner.PathCandidate;
import com.ontos.module.ontology.core.OntologyPathPlanner.PathStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyPathPlannerTest {

    /** 交易域七关系拓扑（对照首发清单；golden 标记与清单一致）。 */
    private OntologySnapshot tradingSnapshot() {
        OntologySnapshot.Concept customer = concept("concept.customer", "customer");
        OntologySnapshot.Concept order = concept("concept.order", "trade");
        OntologySnapshot.Concept orderItem = concept("concept.order_item", "trade");
        OntologySnapshot.Concept product = concept("concept.product", "product");
        OntologySnapshot.Concept channel = concept("concept.channel", "trade");
        OntologySnapshot.Concept event = concept("concept.event", "trade");

        OntologySnapshot.Relation placesOrder = relation("ontology.Customer.placesOrder",
                "concept.customer", "concept.order", "1:N", true, true, "customer.id", "orders.customer_id");
        OntologySnapshot.Relation containsItem = relation("ontology.Order.containsItem",
                "concept.order", "concept.order_item", "1:N", true, true, "orders.id", "order_item.order_id");
        OntologySnapshot.Relation refersProduct = relation("ontology.OrderItem.refersProduct",
                "concept.order_item", "concept.product", "N:1", true, true, "order_item.product_id", "product.id");
        OntologySnapshot.Relation orderToChannel = relation("ontology.Order.attributedToChannel",
                "concept.order", "concept.channel", "N:1", true, true, "orders.channel_id", "channel.id");
        OntologySnapshot.Relation performsEvent = relation("ontology.Customer.performsEvent",
                "concept.customer", "concept.event", "1:N", false, true, "customer.id", "event.customer_id");
        OntologySnapshot.Relation recordsEvent = relation("ontology.Order.recordsEvent",
                "concept.order", "concept.event", "1:N", false, true, "orders.id", "event.order_id");
        OntologySnapshot.Relation eventToChannel = relation("ontology.Event.attributedToChannel",
                "concept.event", "concept.channel", "N:1", false, true, "event.channel_id", "channel.id");

        OntologySnapshot snapshot = new OntologySnapshot();
        snapshot.setOntologyVersion("test");
        snapshot.setConcepts(List.of(customer, order, orderItem, product, channel, event));
        snapshot.setRelations(List.of(placesOrder, containsItem, refersProduct, orderToChannel,
                performsEvent, recordsEvent, eventToChannel));
        return snapshot;
    }

    private OntologySnapshot.Concept concept(String id, String domain) {
        OntologySnapshot.Concept concept = new OntologySnapshot.Concept();
        concept.setId(id);
        concept.setName(id);
        concept.setSummary(id);
        concept.setAliases(List.of(id));
        concept.setIdentityProperty("p-" + id);
        concept.setDomain(domain);
        return concept;
    }

    private OntologySnapshot.Relation relation(String id, String from, String to, String cardinality,
                                               boolean golden, boolean queryable, String fromColumn, String toColumn) {
        OntologySnapshot.Relation relation = new OntologySnapshot.Relation();
        relation.setId(id);
        relation.setName("r");
        relation.setSummary("r");
        relation.setAliases(List.of("r"));
        relation.setFrom(from);
        relation.setTo(to);
        relation.setCardinality(cardinality);
        relation.setReverseLabel("逆" + id);
        relation.setFromColumn(fromColumn);
        relation.setToColumn(toColumn);
        relation.setGoldenPath(golden);
        relation.setQueryable(queryable);
        return relation;
    }

    @Test
    void shouldFindGoldenPathCustomerToProduct() {
        PlanResult result = new OntologyPathPlanner(tradingSnapshot())
                .plan("concept.customer", "concept.product", Set.of(), OntologyPathPlanner.DEFAULT_MAX_HOPS);
        assertEquals(PathStatus.UNIQUE, result.status());
        PathCandidate candidate = result.candidates().get(0);
        assertEquals(3, candidate.hops());
        assertTrue(candidate.allGolden(), "客户→商品应走全 golden 边");
        assertEquals("ontology.Customer.placesOrder", candidate.edges().get(0).relationId());
        assertEquals("ontology.OrderItem.refersProduct", candidate.edges().get(2).relationId());
    }

    @Test
    void shouldPreferGoldenPathOverNonGoldenEqualHops() {
        // 客户→渠道：2 跳的两条路（全 golden 经订单 vs 非 golden 经事件）——golden 优先，不歧义
        PlanResult result = new OntologyPathPlanner(tradingSnapshot())
                .plan("concept.customer", "concept.channel", Set.of(), OntologyPathPlanner.DEFAULT_MAX_HOPS);
        assertEquals(PathStatus.UNIQUE, result.status());
        PathCandidate candidate = result.candidates().get(0);
        assertTrue(candidate.allGolden());
        assertEquals(2, candidate.hops());
        assertEquals("ontology.Order.attributedToChannel", candidate.edges().get(1).relationId());
    }

    @Test
    void shouldTraverseReverseDirection() {
        // 商品→客户：沿 refersProduct / containsItem / placesOrder 全部逆向
        PlanResult result = new OntologyPathPlanner(tradingSnapshot())
                .plan("concept.product", "concept.customer", Set.of(), OntologyPathPlanner.DEFAULT_MAX_HOPS);
        assertEquals(PathStatus.UNIQUE, result.status());
        assertEquals("reverse", result.candidates().get(0).edges().get(0).direction());
    }

    @Test
    void shouldSupportForwardAndReverseOnSameRelation() {
        PlanResult forward = new OntologyPathPlanner(tradingSnapshot())
                .plan("concept.customer", "concept.order", Set.of(), 1);
        PlanResult reverse = new OntologyPathPlanner(tradingSnapshot())
                .plan("concept.order", "concept.customer", Set.of(), 1);
        assertEquals(PathStatus.UNIQUE, forward.status());
        assertEquals("forward", forward.candidates().get(0).edges().get(0).direction());
        assertEquals(PathStatus.UNIQUE, reverse.status());
        assertEquals("reverse", reverse.candidates().get(0).edges().get(0).direction());
    }

    @Test
    void shouldReturnNoneForUnknownConcept() {
        PlanResult result = new OntologyPathPlanner(tradingSnapshot())
                .plan("concept.unknown", "concept.order", Set.of(), 3);
        assertEquals(PathStatus.NONE, result.status());
        assertTrue(result.reason().contains("concept.unknown"));
    }

    @Test
    void shouldReturnNoneWhenMaxHopsExceeded() {
        PlanResult result = new OntologyPathPlanner(tradingSnapshot())
                .plan("concept.customer", "concept.product", Set.of(), 2);
        assertEquals(PathStatus.NONE, result.status());
    }

    @Test
    void shouldExcludeRelationsOutsideAuthorizedDomains() {
        // 授权子图只留 trade：经 customer/product 的边不可达 → 订单→商品 NONE
        PlanResult result = new OntologyPathPlanner(tradingSnapshot())
                .plan("concept.order", "concept.product", Set.of("trade"), 3);
        assertEquals(PathStatus.NONE, result.status());
    }

    @Test
    void shouldReturnAmbiguousWhenEqualPriorityPathsRemain() {
        // 合成拓扑：两条并行的 golden 边 → 歧义（禁止私自选择）
        OntologySnapshot.Concept a = concept("concept.a", "trade");
        OntologySnapshot.Concept b = concept("concept.b", "trade");
        OntologySnapshot.Relation r1 = relation("rel.a.b.path1", "concept.a", "concept.b", "N:1",
                true, true, "a.id", "b.id");
        OntologySnapshot.Relation r2 = relation("rel.a.b.path2", "concept.a", "concept.b", "N:1",
                true, true, "a.id2", "b.id");
        OntologySnapshot snapshot = new OntologySnapshot();
        snapshot.setConcepts(List.of(a, b));
        snapshot.setRelations(List.of(r1, r2));

        PlanResult result = new OntologyPathPlanner(snapshot).plan("concept.a", "concept.b", Set.of(), 3);
        assertEquals(PathStatus.AMBIGUOUS, result.status());
        assertEquals(2, result.candidates().size());
    }

    @Test
    void shouldExcludeNonQueryableRelations() {
        // 唯一通路是非 queryable 关系 → NONE（不可用于路径规划）
        OntologySnapshot.Concept a = concept("concept.a", "trade");
        OntologySnapshot.Concept b = concept("concept.b", "trade");
        OntologySnapshot.Relation blocked = relation("rel.a.b", "concept.a", "concept.b", "N:1",
                false, false, "a.id", "b.id");
        OntologySnapshot snapshot = new OntologySnapshot();
        snapshot.setConcepts(List.of(a, b));
        snapshot.setRelations(List.of(blocked));

        PlanResult result = new OntologyPathPlanner(snapshot).plan("concept.a", "concept.b", Set.of(), 3);
        assertEquals(PathStatus.NONE, result.status());
    }

    @Test
    void shouldReturnZeroHopPathForSameClass() {
        PlanResult result = new OntologyPathPlanner(tradingSnapshot())
                .plan("concept.order", "concept.order", Set.of(), 3);
        assertEquals(PathStatus.UNIQUE, result.status());
        assertEquals(0, result.candidates().get(0).hops());
    }

}
