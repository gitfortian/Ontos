package com.ontos.module.query.core.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.module.ontology.core.OntologyPathPlanner;
import com.ontos.module.ontology.core.BindingResolver;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.ontology.service.manifest.AuthorizedDomainsSupplier;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.springframework.stereotype.Component;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.query.enums.ErrorCodeConstants.PATH_AMBIGUOUS;
import static com.ontos.module.query.enums.ErrorCodeConstants.PATH_GRANULARITY_VIOLATION;
import static com.ontos.module.query.enums.ErrorCodeConstants.PATH_UNREACHABLE;

/**
 * 路径 → Join 解析（M2/W3/T3.1，ADR-0007）。
 *
 * <p>只在已发布关系的显式边上规划：UNIQUE 才展开，AMBIGUOUS/NONE 一律拒绝并带规划原因，
 * 禁止私自挑路。物理列与表名全部来自已发布清单的 Relation / Property，DSL 不携带物理名。
 * 基数校验：沿遍历方向出现 1:N（一对多）即判定粒度扩散，违反「一行一事实」直接拒绝。
 *
 * <p>解析结果回填到 DSL（随 dsl_json 进审计），由 SqlCompiler 用 jOOQ 结构化构造 ON 条件。
 */
@Component
public class JoinPathResolver {

    private final AuthorizedDomainsSupplier authorizedDomains;

    public JoinPathResolver(AuthorizedDomainsSupplier authorizedDomains) {
        this.authorizedDomains = authorizedDomains;
    }

    public void resolve(QueryDsl dsl, PublishedManifest manifest) {
        dsl.setResolvedJoins(new ArrayList<>());
        SemanticManifest semantic = manifest.getSemantic();
        SemanticManifest.Metric metric = semantic.requireMetric(dsl.getMetricId());
        // B0：根的取表按绑定形态解析（v19 直取 table，v20 由聚合属性推导并断言 = 对象主表）
        BindingResolver binding = BindingResolver.of(manifest);
        String rootTable = binding.tableOf(metric);

        Set<String> targetTables = new LinkedHashSet<>();
        for (String dimensionId : nullToEmpty(dsl.getDimensions())) {
            addCrossTable(binding.tableOf(semantic.requireDimension(dimensionId)), rootTable, targetTables);
        }
        for (QueryDsl.Filter filter : nullToEmpty(dsl.getFilters())) {
            addCrossTable(binding.tableOf(semantic.requireDimension(filter.getFieldId())),
                    rootTable, targetTables);
        }
        if (dsl.getTimeRange() != null && dsl.getTimeRange().getField() != null) {
            addCrossTable(binding.tableOf(semantic.requireDimension(dsl.getTimeRange().getField())),
                    rootTable, targetTables);
        }
        if (targetTables.isEmpty()) {
            return;
        }

        OntologySnapshot ontology = manifest.getOntology();
        Map<String, String> conceptByTable = conceptByTable(ontology, binding);
        String rootConcept = conceptByTable.get(rootTable);
        if (rootConcept == null) {
            throw exception(PATH_UNREACHABLE, "指标表无法定位业务对象: " + rootTable);
        }
        Map<String, OntologySnapshot.Relation> relations = relationIndex(ontology);
        OntologyPathPlanner planner = new OntologyPathPlanner(ontology);
        Set<String> domains = authorizedDomains == null ? Set.of() : authorizedDomains.get();

        Set<String> joinedTables = new LinkedHashSet<>();
        joinedTables.add(rootTable);
        // 多个跨表维度可能规划出重叠路径；同一关系只展开一次，避免重复 JOIN 或伪造额外等值条件。
        Set<String> expandedRelations = new LinkedHashSet<>();
        List<QueryDsl.ResolvedJoin> joins = new ArrayList<>();
        for (String targetTable : targetTables) {
            String targetConcept = conceptByTable.get(targetTable);
            if (targetConcept == null) {
                throw exception(PATH_UNREACHABLE, "维度表无法定位业务对象: " + targetTable);
            }
            OntologyPathPlanner.PlanResult plan = planner.plan(
                    rootConcept, targetConcept, domains, OntologyPathPlanner.DEFAULT_MAX_HOPS);
            if (plan.status() == OntologyPathPlanner.PathStatus.AMBIGUOUS) {
                throw exception(PATH_AMBIGUOUS, rootConcept + " -> " + targetConcept + ": " + plan.reason());
            }
            if (plan.status() != OntologyPathPlanner.PathStatus.UNIQUE
                    || plan.candidates().size() != 1) {
                throw exception(PATH_UNREACHABLE,
                        rootConcept + " -> " + targetConcept + ": " + plan.reason());
            }
            for (OntologyPathPlanner.PathEdge edge : plan.candidates().get(0).edges()) {
                validateGranularity(edge);
                if (expandedRelations.contains(edge.relationId())) {
                    // 该边已由前一条路径展开并引入表，复用即可；不得重复加表、也不得退化为 WHERE 条件
                    continue;
                }
                OntologySnapshot.Relation relation = relations.get(edge.relationId());
                if (relation == null || relation.getFromColumn() == null || relation.getToColumn() == null) {
                    throw exception(PATH_UNREACHABLE, "关系缺少 Join 映射: " + edge.relationId());
                }
                String leftColumn = relation.getFromColumn();
                String rightColumn = relation.getToColumn();
                String leftTable = tableOf(leftColumn);
                String rightTable = tableOf(rightColumn);
                if (leftTable == null || rightTable == null) {
                    throw exception(PATH_UNREACHABLE, "关系 Join 映射不是限定列名: " + edge.relationId());
                }
                boolean leftJoined = joinedTables.contains(leftTable);
                boolean rightJoined = joinedTables.contains(rightTable);
                if (!leftJoined && !rightJoined) {
                    throw exception(PATH_UNREACHABLE, "路径与已展开表不连通: " + edge.relationId());
                }
                if (leftJoined && rightJoined) {
                    // 两端表已由其它路径引入，但该关系本身未展开：再加等值约束会静默改变结果，
                    // 按 fail-closed 拒绝，绝不猜测连接（v1.4 §8.2「一行一事实」）。
                    throw exception(PATH_UNREACHABLE,
                            "关系两端已引入但未展开，禁止追加隐式连接: " + edge.relationId());
                }
                String tableToJoin = leftJoined ? rightTable : leftTable;

                QueryDsl.ResolvedJoin join = new QueryDsl.ResolvedJoin();
                join.setRelationId(edge.relationId());
                join.setDirection(edge.direction());
                join.setTable(tableToJoin);
                join.setLeftColumn(leftColumn);
                join.setRightColumn(rightColumn);
                join.setCardinality(effectiveCardinality(edge));
                joins.add(join);
                expandedRelations.add(edge.relationId());
                joinedTables.add(tableToJoin);
            }
        }
        dsl.setResolvedJoins(joins);
    }

    /**
     * 沿遍历方向归一化基数：1:1 / N:1 允许（many 端分组聚合），
     * 1:N / N:M 造成粒度扩散，直接拒绝（v1.4 §8.2 一行一事实）。
     */
    private static void validateGranularity(OntologyPathPlanner.PathEdge edge) {
        String effective = effectiveCardinality(edge);
        if (!"N:1".equals(effective) && !"1:1".equals(effective)) {
            throw exception(PATH_GRANULARITY_VIOLATION,
                    edge.relationId() + " " + edge.direction() + " 归一化基数=" + effective);
        }
    }

    private static String effectiveCardinality(OntologyPathPlanner.PathEdge edge) {
        String cardinality = edge.cardinality();
        if ("1:1".equals(cardinality) || "N:M".equals(cardinality)) {
            return cardinality;
        }
        boolean forward = "forward".equals(edge.direction());
        if ("N:1".equals(cardinality)) {
            return forward ? "N:1" : "1:N";
        }
        if ("1:N".equals(cardinality)) {
            return forward ? "1:N" : "N:1";
        }
        return "UNKNOWN";
    }

    /** 物理表 → 业务对象：由清单 Property 的绑定解析反推（B3 双形态），冲突即拒绝（禁止猜测）。 */
    private static Map<String, String> conceptByTable(OntologySnapshot ontology,
                                                      BindingResolver binding) {
        Map<String, String> result = new HashMap<>();
        for (OntologySnapshot.Property property : nullToEmpty(ontology.getProperties())) {
            String table;
            try {
                table = binding.tableOfProperty(property);
            } catch (RuntimeException ex) {
                // 旧形态裸列名/引用计数属性等无法反推表名的，跳过（join 规划只关心可定位的表）
                continue;
            }
            if (table == null || property.getConceptId() == null) {
                continue;
            }
            String previous = result.putIfAbsent(table, property.getConceptId());
            if (previous != null && !previous.equals(property.getConceptId())) {
                throw exception(PATH_UNREACHABLE, "物理表映射到多个业务对象: " + table);
            }
        }
        return result;
    }

    private static Map<String, OntologySnapshot.Relation> relationIndex(OntologySnapshot ontology) {
        Map<String, OntologySnapshot.Relation> result = new HashMap<>();
        for (OntologySnapshot.Relation relation : nullToEmpty(ontology.getRelations())) {
            result.put(relation.getId(), relation);
        }
        return result;
    }

    /** "schema.table.column" → "schema.table"；"table.column" → "table"；裸列名返回 null。 */
    private static String tableOf(String column) {
        if (column == null) {
            return null;
        }
        int lastDot = column.lastIndexOf('.');
        return lastDot <= 0 ? null : column.substring(0, lastDot);
    }

    private static void addCrossTable(String table, String rootTable, Set<String> targets) {
        if (table != null && !table.equals(rootTable)) {
            targets.add(table);
        }
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

}
