package com.ontos.module.query.core.dependency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ontos.module.query.core.dsl.QueryDsl;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.TopologicalOrderIterator;
import org.springframework.stereotype.Component;

/**
 * 复合意图依赖图定序（M6/W4/T4.1，技术选型 §4.6 JGraphT）：dependsOn 有向图拓扑排序；
 * **环 / 自依赖 / 引用不存在的子意图 → 结构化拒绝**（DEPENDENCY_CYCLE 1_002_000_044，
 * 拒绝转澄清由编排层映射），不猜测执行顺序。
 *
 * <p>行为契约（规格 composite-resultRef v1）：
 * <ul>
 *   <li>`resultRef.fromSubIntentId` 必须出现在本子意图 `dependsOn` 中（校验器把关，本类
 *       只消费 dependsOn 边）；</li>
 *   <li>拓扑序稳定性：同层按提交顺序（TopologicalOrderIterator 对 LRU 构图不保证全序时
 *       按入度零队列自然顺序，提交序入图保证输出确定）；</li>
 *   <li>无依赖复合请求（M2 题集全部形状）返回原提交顺序——回归零变化。</li>
 * </ul>
 */
@Component
public class CompositeDependencyPlanner {

    /**
     * @param subIntents 复合子意图（提交顺序）
     * @return 拓扑序（依赖者后于被依赖者）
     * @throws com.ontos.framework.common.exception.ServiceException 环 / 自依赖 / 缺失引用
     *         （DEPENDENCY_CYCLE 1_002_000_044，detail 带具体子意图）
     */
    public List<QueryDsl.SubIntent> topologicalOrder(List<QueryDsl.SubIntent> subIntents) {
        Map<String, QueryDsl.SubIntent> byId = new LinkedHashMap<>();
        for (QueryDsl.SubIntent sub : subIntents) {
            byId.put(sub.getSubIntentId(), sub);
        }
        DefaultDirectedGraph<String, DefaultEdge> graph =
                new DefaultDirectedGraph<>(DefaultEdge.class);
        for (QueryDsl.SubIntent sub : subIntents) {
            graph.addVertex(sub.getSubIntentId());
        }
        for (QueryDsl.SubIntent sub : subIntents) {
            for (String dependency : sub.getDependsOn()) {
                if (!byId.containsKey(dependency)) {
                    throw dependencyIllegal(sub.getSubIntentId(),
                            "引用不存在的子意图: " + dependency);
                }
                if (dependency.equals(sub.getSubIntentId())) {
                    throw dependencyIllegal(sub.getSubIntentId(), "自依赖");
                }
                graph.addEdge(dependency, sub.getSubIntentId());
            }
        }
        // 环检测（CycleDetector 显式判定——TopologicalOrderIterator 对环会抛库异常而非业务码）
        org.jgrapht.alg.cycle.CycleDetector<String, DefaultEdge> detector =
                new org.jgrapht.alg.cycle.CycleDetector<>(graph);
        if (detector.detectCycles()) {
            List<String> cycleMembers = new ArrayList<>(detector.findCycles());
            throw dependencyIllegal(String.join(", ", cycleMembers), "依赖环");
        }
        TopologicalOrderIterator<String, DefaultEdge> iterator = new TopologicalOrderIterator<>(graph);
        List<String> ordered = new ArrayList<>();
        iterator.forEachRemaining(ordered::add);
        List<QueryDsl.SubIntent> result = new ArrayList<>();
        for (String id : ordered) {
            result.add(byId.get(id));
        }
        return List.copyOf(result);
    }

    /** 是否存在任何依赖边（无依赖复合请求保持 M2 原路径——两阶段全编译后统一执行）。 */
    public boolean hasDependencies(List<QueryDsl.SubIntent> subIntents) {
        return subIntents.stream().anyMatch(sub ->
                sub.getDependsOn() != null && !sub.getDependsOn().isEmpty());
    }

    private static com.ontos.framework.common.exception.ServiceException dependencyIllegal(
            String detail, String kind) {
        return com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception(
                com.ontos.module.query.enums.ErrorCodeConstants.DEPENDENCY_CYCLE,
                kind + ": " + detail);
    }
}
