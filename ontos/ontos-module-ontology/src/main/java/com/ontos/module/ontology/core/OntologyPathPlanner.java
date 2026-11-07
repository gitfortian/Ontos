package com.ontos.module.ontology.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本体路径规划器（M1/T4.1+T4.2）：在已发布关系的显式边上做受限搜索，禁止隐式推导。
 *
 * <p>语义遵循完整可落地方案 v1.4 §7.3 第 8/9 条：
 * <ul>
 *   <li>只在 queryable 的关系边上遍历，可走正向或逆向，跳数受限（≤ maxHops）；</li>
 *   <li>授权子图先行：任一端点概念域不在授权范围内，该边不可达；</li>
 *   <li>优先级固定：Golden Path（全 golden 边的路径）&gt; 最短合法路径；代价函数版本化；</li>
 *   <li>并列候选不分胜负——返回 AMBIGUOUS 并列出候选，澄清交互归 M5，禁止私自选择。</li>
 * </ul>
 */
public class OntologyPathPlanner {

    /** 代价函数版本：排序规则（golden 优先 → 跳数 → 关系 ID 链字典序）变更时升版。 */
    public static final String COST_FUNCTION_VERSION = "path-cost-1.0";

    public static final int DEFAULT_MAX_HOPS = 3;

    public enum PathStatus {
        UNIQUE,
        AMBIGUOUS,
        NONE
    }

    /** 路径上的一条边：relationId + 遍历方向（forward=沿关系正向，reverse=逆向）。 */
    public record PathEdge(String relationId, String direction, String from, String to, String cardinality) {
    }

    public record PathCandidate(List<PathEdge> edges, int hops, boolean allGolden) {
    }

    public record PlanResult(PathStatus status, List<PathCandidate> candidates, String reason) {
    }

    private final OntologySnapshot snapshot;
    private final Map<String, Boolean> goldenByRelation;

    public OntologyPathPlanner(OntologySnapshot snapshot) {
        this.snapshot = snapshot;
        Map<String, Boolean> golden = new java.util.HashMap<>();
        for (OntologySnapshot.Relation relation : snapshot.getRelations()) {
            golden.put(relation.getId(), relation.isGoldenPath());
        }
        this.goldenByRelation = Map.copyOf(golden);
    }

    /**
     * 规划：from → to 的合法路径候选。
     *
     * @param authorizedDomains 授权域集合（T6.2 的最小实现：子图按域收窄）；空集合表示不过滤（调试态）
     */
    public PlanResult plan(String fromClass, String toClass, Set<String> authorizedDomains, int maxHops) {
        Set<String> concepts = new HashSet<>();
        for (OntologySnapshot.Concept concept : snapshot.getConcepts()) {
            concepts.add(concept.getId());
        }
        if (!concepts.contains(fromClass) || !concepts.contains(toClass)) {
            return new PlanResult(PathStatus.NONE, List.of(),
                    "业务对象不存在: " + (!concepts.contains(fromClass) ? fromClass : toClass));
        }

        List<OntologySnapshot.Relation> traversable = traversableRelations(authorizedDomains);
        List<PathCandidate> candidates = new ArrayList<>();
        enumerate(fromClass, toClass, maxHops, traversable,
                new LinkedHashSet<>(), new ArrayList<>(), candidates);

        if (candidates.isEmpty()) {
            return new PlanResult(PathStatus.NONE, List.of(),
                    "跳数 ≤ " + maxHops + " 内无合法路径（或全部被授权子图排除）");
        }
        candidates.sort(priority());
        boolean bestGolden = candidates.get(0).allGolden();
        int bestHops = candidates.get(0).hops();
        List<PathCandidate> best = candidates.stream()
                .filter(candidate -> candidate.allGolden() == bestGolden && candidate.hops() == bestHops)
                .toList();
        if (best.size() > 1) {
            return new PlanResult(PathStatus.AMBIGUOUS, List.copyOf(best),
                    "存在 " + best.size() + " 条同等优先级路径，须澄清（禁止私自选择）");
        }
        return new PlanResult(PathStatus.UNIQUE, List.of(best.get(0)), null);
    }

    /** 可遍历关系：queryable 且两端概念域都在授权范围内。 */
    private List<OntologySnapshot.Relation> traversableRelations(Set<String> authorizedDomains) {
        Set<String> domains = authorizedDomains == null || authorizedDomains.isEmpty()
                ? allDomains()
                : authorizedDomains;
        List<OntologySnapshot.Relation> result = new ArrayList<>();
        for (OntologySnapshot.Relation relation : snapshot.getRelations()) {
            if (!relation.isQueryable()) {
                continue;
            }
            if (domainAllowed(relation.getFrom(), domains) && domainAllowed(relation.getTo(), domains)) {
                result.add(relation);
            }
        }
        return result;
    }

    private boolean domainAllowed(String conceptId, Set<String> domains) {
        OntologySnapshot.Concept concept = snapshot.conceptIndex().get(conceptId);
        return concept != null && concept.getDomain() != null && domains.contains(concept.getDomain());
    }

    private Set<String> allDomains() {
        Set<String> domains = new HashSet<>();
        for (OntologySnapshot.Concept concept : snapshot.getConcepts()) {
            if (concept.getDomain() != null) {
                domains.add(concept.getDomain());
            }
        }
        return domains;
    }

    /** DFS 枚举全部简单路径（图很小，百级节点内可行）；同一关系不重复走。 */
    private void enumerate(String current, String target, int remainingHops,
                           List<OntologySnapshot.Relation> traversable,
                           LinkedHashSet<String> visitedRelations,
                           List<PathEdge> pathSoFar,
                           List<PathCandidate> candidates) {
        if (current.equals(target)) {
            candidates.add(new PathCandidate(List.copyOf(pathSoFar), pathSoFar.size(), allGolden(pathSoFar)));
            return;
        }
        if (remainingHops <= 0) {
            return;
        }
        for (OntologySnapshot.Relation relation : traversable) {
            String next = null;
            String direction = null;
            if (relation.getFrom().equals(current)) {
                next = relation.getTo();
                direction = "forward";
            } else if (relation.getTo().equals(current)) {
                next = relation.getFrom();
                direction = "reverse";
            }
            if (next == null || visitedRelations.contains(relation.getId())) {
                continue;
            }
            visitedRelations.add(relation.getId());
            pathSoFar.add(new PathEdge(relation.getId(), direction, current, next, relation.getCardinality()));
            enumerate(next, target, remainingHops - 1, traversable, visitedRelations, pathSoFar, candidates);
            pathSoFar.remove(pathSoFar.size() - 1);
            visitedRelations.remove(relation.getId());
        }
    }

    private boolean allGolden(List<PathEdge> path) {
        if (path.isEmpty()) {
            return false;
        }
        return path.stream().allMatch(edge -> Boolean.TRUE.equals(goldenByRelation.get(edge.relationId())));
    }

    private static Comparator<PathCandidate> priority() {
        return Comparator.<PathCandidate, Boolean>comparing(candidate -> !candidate.allGolden())
                .thenComparingInt(PathCandidate::hops)
                .thenComparing(candidate -> edgeChain(candidate));
    }

    private static String edgeChain(PathCandidate candidate) {
        return candidate.edges().stream()
                .map(edge -> edge.relationId() + ":" + edge.direction())
                .reduce("", (a, b) -> a + "|" + b);
    }

}
