package com.ontos.module.query.core.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.dsl.QueryDsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B0/v21：渠道维度的双形态。
 *
 * <p>v19 的 {@code dim.channel} 挂在订单表冗余列（{@code orders.channel}），只碰订单一个对象；
 * v21 新增 {@code dim.channel_name} 挂在渠道对象（{@code channel.name}），经
 * {@code ontology.Order.attributedToChannel}（{@code orders.channel_id -> channel.id}）JOIN。
 *
 * <p>本类固定三件事：① v21 结构合法；② 旧维度仍是单表；③ 新维度确实产生 JOIN，
 * 即「订单 × 渠道」两个对象都参与——这是区分「快照列」与「对象引用」的唯一硬证据。
 */
class B0ChannelDimensionTest {

    private final SqlCompiler compiler = new SqlCompiler();

    private static PublishedManifest load(String file) throws Exception {
        Path path = Path.of("..", "..", "ontos-assets", file);
        assertTrue(Files.exists(path), "资产文件缺失: " + path.toAbsolutePath());
        ManifestPayload payload = JsonUtils.parseObject(Files.readString(path), ManifestPayload.class);
        PublishedManifest manifest = new PublishedManifest();
        manifest.setManifestId(file);
        manifest.setSemantic(payload.getSemantic());
        manifest.setOntology(payload.getOntology());
        manifest.setRules(payload.getRules() == null ? List.of() : payload.getRules());
        manifest.setScenarios(payload.getScenarios() == null ? List.of() : payload.getScenarios());
        manifest.setVqrs(payload.getVqrs() == null ? List.of() : payload.getVqrs());
        return manifest;
    }

    private static QueryDsl query(String metricId, List<String> dimensions) {
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId("channel-test");
        dsl.setMode(QueryDsl.AnalysisMode.QUERY);
        dsl.setMetricId(metricId);
        dsl.setDimensions(new ArrayList<>(dimensions));
        dsl.setLimit(100);
        return dsl;
    }

    /** order 与 channel 同属 trade 域，授权两个域即可覆盖。 */
    private static JoinPathResolver resolver() {
        return new JoinPathResolver(() -> Set.of("trade", "customer", "product"));
    }

    @Test
    void shouldAcceptV21Manifest() throws Exception {
        ManifestPayload payload = JsonUtils.parseObject(
                Files.readString(Path.of("..", "..", "ontos-assets", "first-release-v21.json")),
                ManifestPayload.class);

        List<com.ontos.module.ontology.core.ManifestStructureValidator.Violation> violations =
                new com.ontos.module.ontology.core.ManifestStructureValidator().validate(payload);
        assertTrue(violations.isEmpty(), "v21 结构校验失败: " + violations);
        assertEquals("2026.11.12.2", payload.getSemantic().getSemanticVersion());
    }

    @Test
    void shouldBindChannelNameDimensionToChannelObject() throws Exception {
        PublishedManifest v21 = load("first-release-v21.json");

        var channelName = v21.getSemantic().requireDimension("dim.channel_name");
        assertEquals(com.ontos.module.ontology.core.SemanticManifest.BindingForm.V20_SEMANTIC,
                channelName.bindingForm());
        assertEquals("concept.channel", channelName.getObjectId());
        assertEquals("property.channel.name", channelName.getPropertyId());

        // 旧维度仍挂订单对象，两者不可混淆
        var legacyChannel = v21.getSemantic().requireDimension("dim.channel");
        assertEquals("concept.order", legacyChannel.getObjectId());
    }

    @Test
    void shouldKeepLegacyChannelDimensionAsSingleTable() throws Exception {
        PublishedManifest v21 = load("first-release-v21.json");
        QueryDsl dsl = query("metric.gmv", List.of("dim.channel"));

        resolver().resolve(dsl, v21);
        assertTrue(dsl.getResolvedJoins().isEmpty(), "旧渠道维度不应产生 JOIN");

        String sql = compiler.compile(dsl, v21).getSql();
        assertTrue(sql.toLowerCase().contains("from \"orders\""), "FROM 应为订单表: " + sql);
        assertTrue(sql.contains("channel"), "分组列应为订单渠道列: " + sql);
    }

    @Test
    void shouldJoinChannelObjectForChannelNameDimension() throws Exception {
        PublishedManifest v21 = load("first-release-v21.json");
        QueryDsl dsl = query("metric.gmv", List.of("dim.channel_name"));

        resolver().resolve(dsl, v21);
        assertEquals(1, dsl.getResolvedJoins().size(), "新渠道维度必须产生一条 JOIN");
        assertEquals("channel", dsl.getResolvedJoins().get(0).getTable());

        String sql = compiler.compile(dsl, v21).getSql();
        assertTrue(sql.toLowerCase().contains("join"), "应生成 JOIN: " + sql);
        assertTrue(sql.contains("\"channel\""), "应 JOIN 渠道对象: " + sql);
        assertTrue(sql.contains("name"), "分组列应为 channel.name: " + sql);
    }

    @Test
    void shouldProduceDifferentSqlForTheTwoChannelDimensions() throws Exception {
        PublishedManifest v21 = load("first-release-v21.json");

        QueryDsl legacy = query("metric.gmv", List.of("dim.channel"));
        resolver().resolve(legacy, v21);
        QueryDsl object = query("metric.gmv", List.of("dim.channel_name"));
        resolver().resolve(object, v21);

        String legacySql = compiler.compile(legacy, v21).getSql();
        String objectSql = compiler.compile(object, v21).getSql();

        // 两者语义不同，SQL 必须不同——相同就说明新维度没真正走渠道对象
        assertNotEquals(legacySql, objectSql, "两个渠道维度的 SQL 不应相同");
    }
}
