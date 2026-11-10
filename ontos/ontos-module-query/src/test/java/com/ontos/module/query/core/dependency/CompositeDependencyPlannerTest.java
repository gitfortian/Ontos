package com.ontos.module.query.core.dependency;

import java.util.ArrayList;
import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 复合依赖图定序（M6/W4/T4.1）：环 / 自依赖 / 缺失引用 044 拒绝 + 拓扑序 + 无依赖原序。 */
class CompositeDependencyPlannerTest {

    private final CompositeDependencyPlanner planner = new CompositeDependencyPlanner();

    @Test
    void cycleIsRejected() {
        // sub-1 依赖 sub-2，sub-2 依赖 sub-1 —— 环
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planner.topologicalOrder(List.of(
                        sub("sub-1", List.of("sub-2")),
                        sub("sub-2", List.of("sub-1")))));
        assertEquals(1_002_000_044, ex.getCode(), "依赖环 → 044");
        assertTrue(ex.getMessage().contains("依赖环"), ex.getMessage());
    }

    @Test
    void selfDependencyIsRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planner.topologicalOrder(List.of(sub("sub-1", List.of("sub-1")))));
        assertEquals(1_002_000_044, ex.getCode(), "自依赖 → 044");
        assertTrue(ex.getMessage().contains("自依赖"), ex.getMessage());
    }

    @Test
    void missingReferenceIsRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planner.topologicalOrder(List.of(sub("sub-1", List.of("sub-ghost")))));
        assertEquals(1_002_000_044, ex.getCode(), "引用不存在的子意图 → 044");
        assertTrue(ex.getMessage().contains("sub-ghost"), ex.getMessage());
    }

    @Test
    void topologicalOrderPutsDependenciesFirst() {
        // sub-2 依赖 sub-1（提交顺序反着写）→ 拓扑序 sub-1 在前
        List<QueryDsl.SubIntent> ordered = planner.topologicalOrder(List.of(
                sub("sub-2", List.of("sub-1")),
                sub("sub-1", List.of())));
        assertEquals("sub-1", ordered.get(0).getSubIntentId(), "被依赖者先执行");
        assertEquals("sub-2", ordered.get(1).getSubIntentId());
    }

    @Test
    void independentSubsKeepSubmissionOrder() {
        // 无依赖：返回原提交顺序（M2 回归零变化）
        List<QueryDsl.SubIntent> ordered = planner.topologicalOrder(List.of(
                sub("sub-3", List.of()), sub("sub-1", List.of()), sub("sub-2", List.of())));
        assertEquals(List.of("sub-3", "sub-1", "sub-2"),
                ordered.stream().map(QueryDsl.SubIntent::getSubIntentId).toList());
        assertTrue(!planner.hasDependencies(ordered), "无依赖判定");
    }

    private static QueryDsl.SubIntent sub(String id, List<String> dependsOn) {
        QueryDsl.SubIntent sub = new QueryDsl.SubIntent();
        sub.setSubIntentId(id);
        sub.setMetricId("gmv");
        sub.setDependsOn(new ArrayList<>(dependsOn));
        return sub;
    }
}
