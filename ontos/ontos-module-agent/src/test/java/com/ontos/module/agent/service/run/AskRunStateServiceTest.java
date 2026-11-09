package com.ontos.module.agent.service.run;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.agent.dal.dataobject.AskRunDO;
import com.ontos.module.agent.dal.mysql.AskRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * run 状态机迁移表锁定（M5 规格 §3.7）：合法迁移通过、非法迁移抛错、终态不可回退。
 */
class AskRunStateServiceTest {

    private final AskRunMapper mapper = mock(AskRunMapper.class);
    private final AskRunStateService.SessionRunGate gate =
            new AskRunStateService.SessionRunGate(mapper);
    private final RequestContext context =
            new RequestContext("t1", "u1", "s1", 1, null, "tr1", Set.of(), false);

    private AskRunStateService service;

    @BeforeEach
    void setUp() {
        service = new AskRunStateService(mapper, gate);
        when(mapper.findActiveBySession(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void legalTransitionsPassThrough() {
        when(mapper.transition("r1", AskRunDO.PENDING, AskRunDO.RUNNING)).thenReturn(true);
        service.transition("r1", AskRunDO.PENDING, AskRunDO.RUNNING);
        when(mapper.transition("r1", AskRunDO.RUNNING, AskRunDO.WAITING_APPROVAL)).thenReturn(true);
        service.transition("r1", AskRunDO.RUNNING, AskRunDO.WAITING_APPROVAL);
        when(mapper.transition("r1", AskRunDO.WAITING_APPROVAL, AskRunDO.RUNNING)).thenReturn(true);
        service.transition("r1", AskRunDO.WAITING_APPROVAL, AskRunDO.RUNNING);
        when(mapper.transition("r1", AskRunDO.RUNNING, AskRunDO.FINISHED)).thenReturn(true);
        service.transition("r1", AskRunDO.RUNNING, AskRunDO.FINISHED);
    }

    @Test
    void terminalCannotTransitBack() {
        // FINISHED/FAILED/EXPIRED 是终态：迁移表无以终态为 key 的条目 → 一律非法
        assertThrows(ServiceException.class,
                () -> service.transition("r1", AskRunDO.FINISHED, AskRunDO.RUNNING));
        assertThrows(ServiceException.class,
                () -> service.transition("r1", AskRunDO.FAILED, AskRunDO.RUNNING));
        assertThrows(ServiceException.class,
                () -> service.transition("r1", AskRunDO.EXPIRED, AskRunDO.RUNNING));
        // 非法跳跃：PENDING → FINISHED（未执行不得直接完成）
        assertThrows(ServiceException.class,
                () -> service.transition("r1", AskRunDO.PENDING, AskRunDO.FINISHED));
        // WAITING_APPROVAL → FINISHED（审批未决不得直接完成）
        assertThrows(ServiceException.class,
                () -> service.transition("r1", AskRunDO.WAITING_APPROVAL, AskRunDO.FINISHED));
        // 合法：WAITING_APPROVAL → FAILED（审批被拒绝的收尾，T4.2）
        when(mapper.transition("r1", AskRunDO.WAITING_APPROVAL, AskRunDO.FAILED)).thenReturn(true);
        service.transition("r1", AskRunDO.WAITING_APPROVAL, AskRunDO.FAILED);
        // 非法迁移从未触达 DB（fail 在应用层迁移表校验）
        verify(mapper, never()).transition(eq("r1"), eq(AskRunDO.FINISHED), any());
        verify(mapper, never()).transition(eq("r1"), eq(AskRunDO.PENDING), eq(AskRunDO.FINISHED));
    }

    @Test
    void dbMismatchFailsClosed() {
        when(mapper.transition("r1", AskRunDO.RUNNING, AskRunDO.FINISHED)).thenReturn(false);
        assertThrows(ServiceException.class,
                () -> service.transition("r1", AskRunDO.RUNNING, AskRunDO.FINISHED),
                "DB 状态与应用期望不一致必须 fail-closed");
    }

    @Test
    void activeRunBlocksNewRun() {
        when(mapper.findActiveBySession("t1", "u1", "s1")).thenReturn(List.of(new AskRunDO()));
        assertThrows(ServiceException.class, () -> service.create(context, 5),
                "R3：同会话存在活跃 run（含审批等待）时拒绝新 run");
        verify(mapper, never()).insert(any());
    }

    @Test
    void ownershipCheckHidesForeignRun() {
        when(mapper.findById("r9")).thenReturn(Optional.of(runOf("r9", "other-tenant", "u1")));
        assertThrows(ServiceException.class, () -> service.require("r9", context),
                "跨租户/跨用户一律 NOT_FOUND（不泄露存在性）");
    }

    private AskRunDO runOf(String runId, String tenantId, String userId) {
        AskRunDO run = new AskRunDO();
        run.setRunId(runId);
        run.setTenantId(tenantId);
        run.setUserId(userId);
        run.setSessionId("s1");
        run.setStatus(AskRunDO.RUNNING);
        return run;
    }

}
