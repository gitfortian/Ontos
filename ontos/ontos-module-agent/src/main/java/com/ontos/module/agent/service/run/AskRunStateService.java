package com.ontos.module.agent.service.run;

import java.util.Map;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.agent.dal.dataobject.AskRunDO;
import com.ontos.module.agent.dal.mysql.AskRunMapper;
import com.ontos.module.agent.enums.AskErrorCodeConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * run 状态机（M5 规格 §3.7）：
 *
 * <pre>
 * PENDING → RUNNING → WAITING_APPROVAL → RUNNING → FINISHED
 *                    ↘ FAILED（决策拒绝）
 * RUNNING → FINISHED / FAILED
 * </pre>
 *
 * <p>合法迁移**显式枚举**并单测锁定（M4 G-4 教训：终态不可回退，先判定后转移）；
 * 非法迁移直接抛错，绝不静默。启动扫描（R4）：RUNNING→FAILED、WAITING_APPROVAL→EXPIRED，
 * 对齐 M4 审批惰性过期模式。
 */
@Slf4j
@Service
public class AskRunStateService {

    /** 合法迁移表：key=from，value=允许的 to 集合（终态不在 key 中 = 终态不可迁出）。 */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            AskRunDO.PENDING, Set.of(AskRunDO.RUNNING, AskRunDO.FAILED),
            AskRunDO.RUNNING, Set.of(AskRunDO.FINISHED, AskRunDO.FAILED, AskRunDO.WAITING_APPROVAL),
            // FAILED：审批被拒绝的收尾（T4.2）；EXPIRED：TTL 到期收尾
            AskRunDO.WAITING_APPROVAL, Set.of(AskRunDO.RUNNING, AskRunDO.FAILED, AskRunDO.EXPIRED));

    private final AskRunMapper mapper;
    private final SessionRunGate sessionRunGate;

    @Autowired
    public AskRunStateService(AskRunMapper mapper, SessionRunGate sessionRunGate) {
        this.mapper = mapper;
        this.sessionRunGate = sessionRunGate;
    }

    /** 创建 run：同会话存在活跃 run 时拒绝（R3），turn 占用与 run 创建在同一会话状态链路上。 */
    public AskRunDO create(RequestContext context, int occupiedTurn) {
        if (!sessionRunGate.tryAcceptNewRun(context)) {
            throw exception(AskErrorCodeConstants.ASK_RUN_CONFLICT,
                    "同会话存在进行中的 run（含审批等待中），请先完成或等待其收尾");
        }
        AskRunDO run = new AskRunDO();
        run.setTenantId(context.tenantId());
        run.setUserId(context.userId());
        run.setSessionId(context.sessionId());
        run.setTurnIndex(occupiedTurn);
        String runId = mapper.insert(run);
        return mapper.findById(runId).orElseThrow(
                () -> exception(AskErrorCodeConstants.ASK_RUN_CONFLICT, "run 回读失败"));
    }

    /** 显式迁移：非法迁移抛错（终态不可回退；不存在「吞掉」）。 */
    public void transition(String runId, String from, String to) {
        Set<String> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw exception(AskErrorCodeConstants.ASK_RUN_STATE_ILLEGAL, from + " → " + to);
        }
        if (!mapper.transition(runId, from, to)) {
            // DB 状态与应用期望不一致：并发迁移或已收尾，fail-closed
            throw exception(AskErrorCodeConstants.ASK_RUN_STATE_ILLEGAL, from + " → " + to + "（DB 状态不匹配）");
        }
        log.info("[transition][run {} : {} -> {}]", runId, from, to);
    }

    /** 会话最新 run（waiting 中间态回执用）。 */
    public String latestRunId(String tenantId, String userId, String sessionId) {
        return mapper.findActiveBySession(tenantId, userId, sessionId).stream().findFirst()
                .map(AskRunDO::getRunId)
                .orElse(null);
    }

    public AskRunDO require(String runId, RequestContext context) {
        AskRunDO run = mapper.findById(runId)
                .orElseThrow(() -> exception(AskErrorCodeConstants.ASK_RUN_NOT_FOUND, runId));
        if (!run.getTenantId().equals(context.tenantId())
                || !run.getUserId().equals(context.userId())) {
            // 跨租户/跨用户不可见（同码，不泄露存在性）
            throw exception(AskErrorCodeConstants.ASK_RUN_NOT_FOUND, runId);
        }
        return run;
    }

    /**
     * 启动扫描（R4，对齐 M4 审批惰性过期）：进程启动时把遗留 RUNNING / WAITING_APPROVAL 收口——
     * 执行线程崩溃或服务重启后 run 不得永久悬挂。审批单本身不受影响（M4 令牌闭环独立）。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void scanOnStartup() {
        int failed = mapper.failRunning();
        int expired = mapper.expireWaiting();
        if (failed > 0 || expired > 0) {
            log.warn("[scanOnStartup][遗留 run 收口: RUNNING→FAILED={}, WAITING_APPROVAL→EXPIRED={}]",
                    failed, expired);
        }
    }

    /** 会话级 run 闸门（R3）：存在 WAITING_APPROVAL / RUNNING run 时拒绝新 run。 */
    @Slf4j
    @org.springframework.stereotype.Component
    public static class SessionRunGate {

        private final AskRunMapper mapper;

        public SessionRunGate(AskRunMapper mapper) {
            this.mapper = mapper;
        }

        public boolean tryAcceptNewRun(RequestContext context) {
            return mapper.findActiveBySession(context.tenantId(), context.userId(),
                    context.sessionId()).isEmpty();
        }
    }
}
