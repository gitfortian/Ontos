package com.ontos.module.catalog.service.skill;

import java.util.List;
import java.util.Optional;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.catalog.dal.dataobject.SkillRecord;
import com.ontos.module.catalog.dal.mysql.JdbcSkillRepository;
import com.ontos.module.ontology.core.ManifestPayload;
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
 * Skill 生命周期（M6/W3/T3.1）：封闭迁移 + **审核人记录**（approve 留痕、publish 前置校验
 * reviewer、published 必有审核人）+ 回滚生成补丁草稿。
 */
class SkillLifecycleServiceTest {

    private JdbcSkillRepository repository;
    private SkillLifecycleService service;

    @BeforeEach
    void setUp() {
        repository = mock(JdbcSkillRepository.class);
        service = new SkillLifecycleService(repository);
    }

    @Test
    void fullLifecycleRecordsReviewer() {
        when(repository.findById("skill.query.basic")).thenReturn(Optional.of(
                record("reviewing", null)));

        SkillRecord approved = service.approve("skill.query.basic", "reviewer-a");
        assertEquals("reviewing", approved.getStatus(), "审核动作不改状态");
        assertEquals("reviewer-a", approved.getReviewer(), "审核人留痕");

        when(repository.findById("skill.query.basic")).thenReturn(Optional.of(approved));
        SkillRecord published = service.publish("skill.query.basic", emptyPayload());
        assertEquals("published", published.getStatus());
        assertEquals("reviewer-a", published.getDefinition().getReviewer(),
                "审核人同步进 definition（列唯一真源纪律）");
    }

    @Test
    void publishWithoutReviewerIsRejected() {
        when(repository.findById("skill.query.basic")).thenReturn(Optional.of(
                record("reviewing", null)));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.publish("skill.query.basic", emptyPayload()));
        assertEquals(1_005_000_004, ex.getCode(), "发布前必须完成人工审核");
    }

    @Test
    void draftCannotSkipReview() {
        when(repository.findById("skill.query.basic")).thenReturn(Optional.of(
                record("draft", null)));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.publish("skill.query.basic", emptyPayload()));
        assertEquals(1_005_000_003, ex.getCode(), "draft → published 越步拒绝");
    }

    @Test
    void rejectReturnsToDraftKeepingReviewerForTraceability() {
        when(repository.findById("skill.query.basic")).thenReturn(Optional.of(
                record("reviewing", "reviewer-a")));
        SkillRecord rejected = service.reject("skill.query.basic");
        assertEquals("draft", rejected.getStatus());
        assertEquals("reviewer-a", rejected.getReviewer(), "退回保留审核记录供追溯");
    }

    @Test
    void rollbackFromRetiredGeneratesPatchDraft() {
        when(repository.findById("skill.query.basic")).thenReturn(Optional.of(
                record("retired", "reviewer-a")));
        ManifestPayload.SkillMeta snapshot = meta();
        snapshot.setVersion("1.2.3");
        SkillRecord rolledBack = service.rollback("skill.query.basic", snapshot, "carol");
        assertEquals("draft", rolledBack.getStatus());
        assertEquals("1.2.4", rolledBack.getVersion(), "回滚生成 patch+1 草稿");
        assertEquals("carol", rolledBack.getOwner());
        verify(repository).insert(any(SkillRecord.class));
    }

    @Test
    void rollbackFromDraftIsRejected() {
        when(repository.findById("skill.query.basic")).thenReturn(Optional.of(
                record("draft", null)));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.rollback("skill.query.basic", meta(), "carol"));
        assertEquals(1_005_000_003, ex.getCode());
    }

    @Test
    void rollbackNonSemverSnapshotIsRejected() {
        when(repository.findById("skill.query.basic")).thenReturn(Optional.of(
                record("published", "reviewer-a")));
        ManifestPayload.SkillMeta snapshot = meta();
        snapshot.setVersion("2026.11");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.rollback("skill.query.basic", snapshot, "carol"));
        assertEquals(1_005_000_008, ex.getCode());
    }

    // ---------- fixtures ----------

    private static SkillRecord record(String status, String reviewer) {
        SkillRecord record = new SkillRecord();
        record.setId("skill.query.basic");
        record.setName("基础查询");
        record.setSummary("基础查询");
        record.setAnalysisMode("QUERY");
        record.setStatus(status);
        record.setOwner("platform-data");
        record.setVersion("1.0.0");
        record.setReviewer(reviewer);
        record.setImplementationRef("builtin.QUERY.basic@1.0.0");
        record.setDefinition(meta());
        return record;
    }

    private static ManifestPayload.SkillMeta meta() {
        ManifestPayload.SkillMeta skill = new ManifestPayload.SkillMeta();
        skill.setSkillId("skill.query.basic");
        skill.setName("基础查询");
        skill.setOwner("platform-data");
        skill.setVersion("1.0.0");
        skill.setStatus("published");
        skill.setAnalysisMode("QUERY");
        skill.setImplementationRef("builtin.QUERY.basic@1.0.0");
        skill.setReviewer("reviewer-a");
        return skill;
    }

    private static ManifestPayload emptyPayload() {
        ManifestPayload payload = new ManifestPayload();
        payload.setSkills(List.of());
        payload.setScenarios(List.of());
        return payload;
    }
}
