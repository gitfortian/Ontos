package com.ontos.module.ontology.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

/**
 * 进程内不可变本体图。只在发布快照加载时构建一次，运行时只读。
 */
public final class OntologyGraph {

    private final OntologySnapshot snapshot;
    private final Graph<String, DefaultEdge> graph;

    public OntologyGraph(OntologySnapshot snapshot) {
        this.snapshot = snapshot;
        this.graph = new DefaultDirectedGraph<>(DefaultEdge.class);
        snapshot.getConcepts().forEach(concept -> graph.addVertex(concept.getId()));
        for (OntologySnapshot.Relation relation : snapshot.getRelations()) {
            if (graph.containsVertex(relation.getFrom()) && graph.containsVertex(relation.getTo())) {
                graph.addEdge(relation.getFrom(), relation.getTo());
            }
        }
    }

    public OntologySnapshot snapshot() {
        return snapshot;
    }

    /** 显式关系路径查找，超过 maxHops 直接放弃，不做隐式推导。 */
    public Optional<List<String>> shortestPath(String from, String to, int maxHops) {
        if (!graph.containsVertex(from) || !graph.containsVertex(to)) {
            return Optional.empty();
        }
        if (from.equals(to)) {
            return Optional.of(List.of(from));
        }
        Map<String, String> parent = new HashMap<>();
        Map<String, Integer> depth = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        depth.put(from, 0);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDepth = depth.get(current);
            if (currentDepth >= maxHops) {
                continue;
            }
            for (DefaultEdge edge : graph.outgoingEdgesOf(current)) {
                String next = graph.getEdgeTarget(edge);
                if (depth.containsKey(next)) {
                    continue;
                }
                parent.put(next, current);
                depth.put(next, currentDepth + 1);
                if (next.equals(to)) {
                    return Optional.of(reconstruct(parent, from, to));
                }
                queue.add(next);
            }
        }
        return Optional.empty();
    }

    public Set<String> reachableFrom(String from, int maxHops) {
        Set<String> visited = new LinkedHashSet<>();
        if (!graph.containsVertex(from)) {
            return visited;
        }
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        visited.add(from);
        Map<String, Integer> depth = new HashMap<>();
        depth.put(from, 0);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (depth.get(current) >= maxHops) {
                continue;
            }
            for (DefaultEdge edge : graph.outgoingEdgesOf(current)) {
                String next = graph.getEdgeTarget(edge);
                if (visited.add(next)) {
                    depth.put(next, depth.get(current) + 1);
                    queue.add(next);
                }
            }
        }
        return Collections.unmodifiableSet(visited);
    }

    private static List<String> reconstruct(Map<String, String> parent, String from, String to) {
        List<String> path = new ArrayList<>();
        String cursor = to;
        while (cursor != null) {
            path.add(cursor);
            if (cursor.equals(from)) {
                break;
            }
            cursor = parent.get(cursor);
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }
}
