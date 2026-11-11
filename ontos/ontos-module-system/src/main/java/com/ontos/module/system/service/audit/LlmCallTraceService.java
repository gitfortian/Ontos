package com.ontos.module.system.service.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ontos.framework.common.trace.LlmCallRecord;
import com.ontos.framework.common.trace.LlmCallTraceSink;
import com.ontos.module.system.dal.dataobject.LlmCallTraceDO;
import com.ontos.module.system.dal.mysql.LlmCallTraceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 模型调用追踪（M7/W5/T5.1，V20）：{@link LlmCallTraceSink} 的 JDBC 实现 + 查询入口。
 *
 * <p>硬约束：
 * <ul>
 *   <li><b>不阻断业务</b>：{@link #record} 内部吞掉持久化异常（只留 warn 日志）——
 *       观测是旁路，不能把「追踪表写不进去」变成「问数失败」；</li>
 *   <li><b>不写脏行</b>：tenantId 为空直接跳过（租户是隔离维度，缺租户的记录不可查询也不可删除）；</li>
 *   <li><b>脱敏</b>：只落元数据，错误信息截断到 500 字符，不存提示词/输出原文。</li>
 * </ul>
 */
@Slf4j
@Service
public class LlmCallTraceService implements LlmCallTraceSink {

    /** error_message 列宽（与 V20 一致）。 */
    static final int MAX_ERROR_MESSAGE = 500;

    private final LlmCallTraceMapper mapper;

    public LlmCallTraceService(LlmCallTraceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(LlmCallRecord record) {
        if (record == null || record.tenantId() == null || record.tenantId().isBlank()) {
            return;
        }
        try {
            mapper.insert(toDO(record));
        } catch (RuntimeException ex) {
            log.warn("[record][模型调用追踪写入失败，已忽略: tenant={} purpose={} model={}] {}",
                    record.tenantId(), record.purpose(), record.model(), ex.toString());
        }
    }

    /** 按 traceId 查询一次请求的模型调用（时间正序）。 */
    public List<Map<String, Object>> listByTrace(String tenantId, String traceId, int limit) {
        return mapper.selectByTraceId(tenantId, traceId, limit).stream()
                .map(LlmCallTraceService::toView).toList();
    }

    /** 最近模型调用（无 traceId 时的观测入口）。 */
    public List<Map<String, Object>> recent(String tenantId, int limit) {
        return mapper.selectRecent(tenantId, limit).stream()
                .map(LlmCallTraceService::toView).toList();
    }

    private static LlmCallTraceDO toDO(LlmCallRecord record) {
        LlmCallTraceDO item = new LlmCallTraceDO();
        item.setTenantId(record.tenantId());
        item.setTraceId(blankToNull(record.traceId()));
        item.setSessionId(blankToNull(record.sessionId()));
        item.setTurnIndex(record.turnIndex());
        item.setPurpose(blankToNull(record.purpose()));
        item.setModel(blankToNull(record.model()));
        item.setInputTokens(record.inputTokens());
        item.setOutputTokens(record.outputTokens());
        item.setTotalTokens(record.totalTokens());
        item.setLatencyMs(record.latencyMs());
        item.setStatus(record.status());
        item.setErrorType(truncate(blankToNull(record.errorType()), 128));
        item.setErrorMessage(truncate(blankToNull(record.errorMessage()), MAX_ERROR_MESSAGE));
        item.setFinishReason(truncate(blankToNull(record.finishReason()), 64));
        return item;
    }

    private static Map<String, Object> toView(LlmCallTraceDO item) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("traceId", item.getTraceId());
        view.put("sessionId", item.getSessionId());
        view.put("turnIndex", item.getTurnIndex());
        view.put("purpose", item.getPurpose());
        view.put("model", item.getModel());
        view.put("inputTokens", item.getInputTokens());
        view.put("outputTokens", item.getOutputTokens());
        view.put("totalTokens", item.getTotalTokens());
        view.put("latencyMs", item.getLatencyMs());
        view.put("status", item.getStatus());
        view.put("errorType", item.getErrorType());
        view.put("errorMessage", item.getErrorMessage());
        view.put("finishReason", item.getFinishReason());
        view.put("createdAt", item.getCreatedAt());
        return view;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}
