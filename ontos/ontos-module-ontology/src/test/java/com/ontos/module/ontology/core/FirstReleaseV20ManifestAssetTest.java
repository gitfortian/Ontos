package com.ontos.module.ontology.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ontos.framework.common.util.json.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B0/W3：v20 首发清单（三层化形态）的结构与迁移断言。
 *
 * <p>与 {@link FirstReleaseManifestAssetTest}（v2 事件映射）互补。本类聚焦三件事：
 * ① v20 能通过结构校验；② 每个指标/维度都已是 {@code objectId + propertyId} 形态；
 * ③ **E2 物理列不变**——v19 与 v20 逐条比对，物理列必须完全相同。
 */
class FirstReleaseV20ManifestAssetTest {

    private static ManifestPayload load(String file) throws Exception {
        Path path = Path.of("..", "..", "ontos-assets", file);
        assertTrue(Files.exists(path), "资产文件缺失: " + path.toAbsolutePath());
        return JsonUtils.parseObject(Files.readString(path), ManifestPayload.class);
    }

    @Test
    void shouldAcceptV20Manifest() throws Exception {
        ManifestPayload payload = load("first-release-v20.json");
        List<ManifestStructureValidator.Violation> violations =
                new ManifestStructureValidator().validate(payload);

        assertTrue(violations.isEmpty(), "v20 清单结构校验失败: " + violations);
        assertEquals("2026.11.12", payload.getOntology().getOntologyVersion());
        assertEquals("2026.11.12.1", payload.getSemantic().getSemanticVersion());
    }

    @Test
    void shouldCarrySemanticBindingOnEveryAsset() throws Exception {
        ManifestPayload payload = load("first-release-v20.json");

        for (SemanticManifest.Metric metric : payload.getSemantic().getMetrics()) {
            if (metric.getRuleId() != null) {
                assertEquals(SemanticManifest.BindingForm.RULE_ONLY, metric.bindingForm(),
                        "布尔判定指标应为 RULE_ONLY: " + metric.getId());
                continue;
            }
            assertEquals(SemanticManifest.BindingForm.V20_SEMANTIC, metric.bindingForm(),
                    "指标应为 v20 形态: " + metric.getId());
            assertNotNull(metric.getObjectId(), "缺 objectId: " + metric.getId());
            assertNotNull(metric.getPropertyId(), "缺 propertyId: " + metric.getId());
            assertNull(metric.getTable(), "v20 不应残留 table: " + metric.getId());
            assertNull(metric.getColumn(), "v20 不应残留 column: " + metric.getId());
        }
        for (SemanticManifest.Dimension dimension : payload.getSemantic().getDimensions()) {
            assertEquals(SemanticManifest.BindingForm.V20_SEMANTIC, dimension.bindingForm(),
                    "维度应为 v20 形态: " + dimension.getId());
            assertNull(dimension.getTable(), "v20 不应残留 table: " + dimension.getId());
            assertNull(dimension.getColumn(), "v20 不应残留 column: " + dimension.getId());
        }
    }

    /**
     * E2：迁移保持物理列不变。
     *
     * <p>v19 的 {@code table.column} 与 v20 对应属性的 {@code column} 必须逐条相等；
     * 这是「同一 DSL 在 v19 与 v20 清单下编译产物逐字相同」的前提。
     */
    @Test
    void shouldKeepPhysicalColumnsIdenticalToV19() throws Exception {
        ManifestPayload v19 = load("first-release-v19.json");
        ManifestPayload v20 = load("first-release-v20.json");

        Map<String, String> v20PropertyColumn = v20.getOntology().getProperties().stream()
                .collect(Collectors.toMap(OntologySnapshot.Property::getId,
                        OntologySnapshot.Property::getColumn));

        for (SemanticManifest.Metric oldMetric : v19.getSemantic().getMetrics()) {
            if (oldMetric.getRuleId() != null) {
                continue;
            }
            SemanticManifest.Metric newMetric = byId(v20.getSemantic().getMetrics(),
                    SemanticManifest.Metric::getId, oldMetric.getId());
            String expected = oldMetric.getTable() + "." + oldMetric.getColumn();
            assertEquals(expected, v20PropertyColumn.get(newMetric.getPropertyId()),
                    "指标物理列变了（E2 违反）: " + oldMetric.getId());
        }
        for (SemanticManifest.Dimension oldDimension : v19.getSemantic().getDimensions()) {
            SemanticManifest.Dimension newDimension = byId(v20.getSemantic().getDimensions(),
                    SemanticManifest.Dimension::getId, oldDimension.getId());
            String expected = oldDimension.getTable() + "." + oldDimension.getColumn();
            assertEquals(expected, v20PropertyColumn.get(newDimension.getPropertyId()),
                    "维度物理列变了（E2 违反）: " + oldDimension.getId());
        }
    }

    /** 迁移新增 4 个属性，且都挂在订单对象上。 */
    @Test
    void shouldAddFourOrderProperties() throws Exception {
        ManifestPayload v19 = load("first-release-v19.json");
        ManifestPayload v20 = load("first-release-v20.json");

        assertEquals(v19.getOntology().getProperties().size() + 4,
                v20.getOntology().getProperties().size(), "属性数应为 v19 + 4");

        Map<String, OntologySnapshot.Property> byId = v20.getOntology().getProperties().stream()
                .collect(Collectors.toMap(OntologySnapshot.Property::getId, p -> p));
        List<String> expected = List.of(
                "property.order.customer_id", "property.order.refund_amount",
                "property.order.channel", "property.order.region");
        for (String id : expected) {
            OntologySnapshot.Property property = byId.get(id);
            assertNotNull(property, "缺少新属性: " + id);
            assertEquals("concept.order", property.getConceptId(), "新属性应挂订单对象: " + id);
        }
        // 命名冲突守则：这两个不得复用既有同名（不同物理列）
        assertEquals("下单客户编号", byId.get("property.order.customer_id").getName());
        assertEquals("订单退款金额", byId.get("property.order.refund_amount").getName());
        assertFalse(byId.get("property.order.customer_id").isLookupProjectable(),
                "下单客户编号默认不开放 lookup 投影");
    }

    private static <T> T byId(List<T> items, Function<T, String> idOf, String id) {
        return items.stream()
                .filter(item -> id.equals(idOf.apply(item)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("v20 缺少对应资产: " + id));
    }
}
