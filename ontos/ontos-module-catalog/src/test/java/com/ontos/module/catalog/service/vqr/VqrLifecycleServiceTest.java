package com.ontos.module.catalog.service.vqr;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.catalog.dal.dataobject.VqrRecord;
import com.ontos.module.catalog.dal.mysql.JdbcVqrRepository;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.SemanticManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VQR 生命周期（M6/W3/T3.4）：封闭迁移 + 审核人记录 + **入库七条件发布闸门**（§11.2）
 * + 证据外置边界（D4：catalog 只验证据引用，不连库）+ 回滚补丁草稿。
 */
class VqrLifecycleServiceTest {

    private JdbcVqrRepository repository;
    private VqrLifecycleService service;

    @BeforeEach
    void setUp() {
        repository = mock(JdbcVqrRepository.class);
        service = new VqrLifecycleService(repository);
    }

    @Test
    void fullLifecycleRecordsReviewerAndPublishes() {
        when(repository.findById("vqr.sales.daily_gmv_by_channel")).thenReturn(Optional.of(
                record("reviewing", null, fullDefinition())));

        VqrRecord approved = service.approve("vqr.sales.daily_gmv_by_channel", "reviewer-a");
        assertEquals("reviewing", approved.getStatus(), "审核动作不改状态");
        assertEquals("reviewer-a", approved.getReviewer(), "审核人留痕");

        when(repository.findById("vqr.sales.daily_gmv_by_channel")).thenReturn(Optional.of(approved));
        VqrRecord published = service.publish("vqr.sales.daily_gmv_by_channel", targetPayload());
        assertEquals("published", published.getStatus());
        // 列是 reviewer 的唯一真源（definition 同步发生在仓库 params 写库时，单测 mock 仓库不覆盖）
        assertEquals("reviewer-a", published.getReviewer());
        verify(repository, org.mockito.Mockito.times(2)).update(any());
    }

    @Test
    void publishWithoutReviewerIsRejected() {
        when(repository.findById("vqr.sales.daily_gmv_by_channel")).thenReturn(Optional.of(
                record("reviewing", null, fullDefinition())));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.publish("vqr.sales.daily_gmv_by_channel", targetPayload()));
        assertEquals(1_005_000_004, ex.getCode(), "发布前必须完成人工审核");
    }

    @Test
    void publishWithoutEvidenceRefsFailsAdmissionWithConditionNumbers() {
        // 条件 3–6（D4 证据外置）：证据引用缺失 → 七条件闸门拒绝，消息带条件编号
        ManifestPayload.VqrMeta definition = fullDefinition();
        definition.setGoldenRef(null);
        definition.setResultCheckRef(null);
        when(repository.findById("vqr.sales.daily_gmv_by_channel")).thenReturn(Optional.of(
                record("reviewing", "reviewer-a", definition)));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.publish("vqr.sales.daily_gmv_by_channel", targetPayload()));
        assertTrue(ex.getMessage().contains("条件3"), "缺 Golden 证据引用: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("条件4-6"), "缺结果校验证据引用: " + ex.getMessage());
    }

    @Test
    void publishWithUnboundAssetFailsAdmission() {
        // 条件 2（依赖绑定稳定）：目标清单没有模板引用的资产 → 拒绝
        when(repository.findById("vqr.sales.daily_gmv_by_channel")).thenReturn(Optional.of(
                record("reviewing", "reviewer-a", fullDefinition())));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.publish("vqr.sales.daily_gmv_by_channel", emptyPayload()));
        assertTrue(ex.getMessage().contains("引用资产不存在或未发布"),
                "绑定稳定按目标清单判定: " + ex.getMessage());
    }

    @Test
    void draftCreationSkipsAssetBinding() {
        // 草稿创建期：模板引用的资产可尚未进清单（结构校验不含绑定）
        ManifestPayload.VqrMeta definition = fullDefinition();
        definition.setStatus("draft");
        VqrRecord created = service.createDraft(definition);
        assertEquals("draft", created.getStatus());
        verify(repository).insert(any());
    }

    @Test
    void retireAndRollbackProducePatchDraft() {
        when(repository.findById("vqr.sales.daily_gmv_by_channel")).thenReturn(Optional.of(
                record("published", "reviewer-a", fullDefinition())));
        VqrRecord retired = service.retire("vqr.sales.daily_gmv_by_channel");
        assertEquals("retired", retired.getStatus());

        ManifestPayload.VqrMeta snapshot = fullDefinition();
        snapshot.setVersion("1.0.0");
        VqrRecord rolledBack = service.rollback("vqr.sales.daily_gmv_by_channel", snapshot, "operator-b");
        assertEquals("draft", rolledBack.getStatus(), "回滚 = 补丁草稿");
        assertEquals("1.0.1", rolledBack.getVersion(), "patch+1");
        assertEquals("operator-b", rolledBack.getOwner());
    }

    @Test
    void rollbackFromDraftIsRejected() {
        when(repository.findById("vqr.sales.daily_gmv_by_channel")).thenReturn(Optional.of(
                record("draft", null, fullDefinition())));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.rollback("vqr.sales.daily_gmv_by_channel", fullDefinition(), "op"));
        assertEquals(1_005_000_003, ex.getCode(), "draft/reviewing 没有已发布版本可回");
    }

    // ---------- fixtures ----------

    private static ManifestPayload.VqrMeta fullDefinition() {
        ManifestPayload.VqrMeta vqr = new ManifestPayload.VqrMeta();
        vqr.setVqrId("vqr.sales.daily_gmv_by_channel");
        vqr.setName("近 N 天各渠道成交金额");
        vqr.setOwner("platform-data");
        vqr.setVersion("1.0.0");
        vqr.setStatus("reviewing");
        vqr.setQuestionPattern("近{days}天各渠道成交金额");
        vqr.setDslTemplate(Map.of(
                "mode", "query",
                "metric_id", "metric.gmv",
                "dimensions", List.of("dim.channel"),
                "time_expression_raw", "近 ${param.days} 天"));
        vqr.setParamsSchema(Map.of("days", Map.of("type", "integer")));
        vqr.setGoldenRef("golden:m6-golden-v1#golden-gmv-by-channel");
        vqr.setResultCheckRef("e2e:t24-e2e#direct-gmv-query-compiled");
        vqr.setFreshnessLimitHours(24);
        vqr.setInvalidationConditions(List.of(
                Map.of("assetId", "metric.gmv", "trigger", "deprecated")));
        return vqr;
    }

    private static VqrRecord record(String status, String reviewer, ManifestPayload.VqrMeta definition) {
        VqrRecord record = new VqrRecord();
        record.setId("vqr.sales.daily_gmv_by_channel");
        record.setName(definition.getName());
        record.setSummary(definition.getName());
        record.setStatus(status);
        record.setOwner(definition.getOwner());
        record.setReviewer(reviewer);
        record.setVersion(definition.getVersion());
        record.setQuestionPattern(definition.getQuestionPattern());
        record.setDslTemplate(definition.getDslTemplate());
        record.setParamsSchema(definition.getParamsSchema());
        record.setGoldenRef(definition.getGoldenRef());
        record.setResultCheckRef(definition.getResultCheckRef());
        record.setFreshnessLimitHours(definition.getFreshnessLimitHours());
        record.setInvalidationConditions(definition.getInvalidationConditions());
        record.setDefinition(definition);
        return record;
    }

    private static ManifestPayload targetPayload() {
        ManifestPayload payload = new ManifestPayload();
        SemanticManifest semantic = new SemanticManifest();
        SemanticManifest.Metric gmv = new SemanticManifest.Metric();
        gmv.setId("metric.gmv");
        SemanticManifest.Dimension channel = new SemanticManifest.Dimension();
        channel.setId("dim.channel");
        semantic.setMetrics(List.of(gmv));
        semantic.setDimensions(List.of(channel));
        payload.setSemantic(semantic);
        return payload;
    }

    private static ManifestPayload emptyPayload() {
        return new ManifestPayload();
    }
}
