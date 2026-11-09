package com.ontos.module.system.service.approval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.framework.common.pojo.PageResult;
import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.system.controller.admin.approval.vo.ApprovalRespVO;
import com.ontos.module.system.dal.dataobject.ApprovalRequestDO;
import com.ontos.module.system.dal.mysql.ApprovalRequestMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.system.enums.ErrorCodeConstants.APPROVAL_DECISION_INVALID;
import static com.ontos.module.system.enums.ErrorCodeConstants.APPROVAL_NOT_FOUND;
import static com.ontos.module.system.enums.ErrorCodeConstants.APPROVAL_PAYLOAD_INVALID;
import static com.ontos.module.system.enums.ErrorCodeConstants.APPROVAL_SELF_DECISION;

/**
 * 审批编排：列表按租户隔离，决策人取自认证上下文，且仅允许处理 PENDING 单据。
 *
 * <p>M4/W2：创建入口（{@link #create}，敏感累计触发方调用，与事件记账同事务）+
 * 放行令牌（{@link #decide} APPROVED 时生成目标指纹、{@link #consumeGrant} 单次消费）。
 * 惰性过期与启动扫描归 W3/T3.1。
 */
@Service
public class ApprovalService {

    /** 敏感累计触发（§4.8.3 / M4 规格 §3.2）。 */
    public static final String KIND_SENSITIVE_SEQUENCE = "SENSITIVE_SEQUENCE";

    private static final Set<String> VALID_DECISIONS = Set.of("APPROVED", "REJECTED");

    private final ApprovalRequestMapper mapper;

    public ApprovalService(ApprovalRequestMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 创建审批单（内部入口，不对 Controller 暴露）。
     *
     * @param payloadJson 触发上下文（sensitive_key / 层级 / 窗口计数 / request_id / filters_hash 等）
     * @return 审批单 ID
     */
    public String create(String tenantId, String userId, String sessionId, Integer turnIndex,
                         String kind, String payloadJson, String reason) {
        if (!KIND_SENSITIVE_SEQUENCE.equals(kind)) {
            // M4 只有敏感序列一种 kind；未来 kind（label 判定 / 域组合）随对应能力一并开放
            throw exception(APPROVAL_DECISION_INVALID);
        }
        return mapper.insert(tenantId, userId, sessionId, turnIndex, kind, payloadJson, reason);
    }

    /**
     * 审批决策（T2.3）：APPROVED 生成放行目标指纹（换 filters 不放行的比对基准）；
     * 决策人 ≠ 申请人（自审批禁止）。
     */
    public void decide(String id, String tenantId, String decision, String reason, String decidedBy) {
        if (!VALID_DECISIONS.contains(decision)) {
            throw exception(APPROVAL_DECISION_INVALID);
        }
        ApprovalRequestDO request = mapper.selectById(id, tenantId)
                .orElseThrow(() -> exception(APPROVAL_NOT_FOUND));
        if (decidedBy != null && decidedBy.equals(request.getUserId())) {
            throw exception(APPROVAL_SELF_DECISION);
        }
        String fingerprint = "APPROVED".equals(decision)
                ? grantFingerprint(request) : null;
        if (mapper.decide(id, tenantId, decision, reason, decidedBy, fingerprint) != 1) {
            // SQL 层兜底（自审批条件/状态竞争）与单据不存在同归 NOT_FOUND 语义
            throw exception(APPROVAL_NOT_FOUND);
        }
    }

    /**
     * 放行令牌单次消费（T2.3）：指纹逐字段比对 + 消费原子置位，消费后不可复用。
     * 调用方（查询联动）在校验失败时拒绝该次查询。
     */
    public boolean consumeGrant(String tenantId, String userId, String approvalId,
                                String fingerprint) {
        return mapper.consumeGrant(tenantId, userId, approvalId, fingerprint);
    }

    /**
     * 放行目标指纹（规格 §3.3 可测定义，公式全局唯一）：hash(user_id + tenant_id +
     * sensitive_key + scope_hash + filters_hash)，与 sensitive_query_event 对应字段同源同义。
     * 决策侧从审批 payload 反解生成；消费侧（查询联动）从当前请求直接计算后比对。
     */
    public static String fingerprint(String userId, String tenantId, String sensitiveKey,
                                     String scopeHash, String filtersHash) {
        return sha256(userId + "|" + tenantId + "|" + sensitiveKey + "|" + scopeHash + "|"
                + filtersHash);
    }

    static String grantFingerprint(ApprovalRequestDO request) {
        Map<String, Object> payload;
        try {
            payload = JsonUtils.parseObject(request.getPayload(),
                    new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException ex) {
            throw exception(APPROVAL_PAYLOAD_INVALID);
        }
        if (payload == null || payload.get("sensitiveKey") == null
                || payload.get("filtersHash") == null) {
            throw exception(APPROVAL_PAYLOAD_INVALID);
        }
        return fingerprint(String.valueOf(payload.getOrDefault("userId", request.getUserId())),
                request.getTenantId(), String.valueOf(payload.get("sensitiveKey")),
                String.valueOf(payload.getOrDefault("scopeHash", "")),
                String.valueOf(payload.get("filtersHash")));
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    public PageResult<ApprovalRespVO> page(String tenantId, String status, Integer pageNo, Integer pageSize) {
        int size = pageSize == null ? 20 : Math.min(Math.max(pageSize, 1), 200);
        int no = pageNo == null ? 1 : Math.max(pageNo, 1);
        int offset = (no - 1) * size;
        List<ApprovalRespVO> list = mapper.selectPage(tenantId, status, size, offset).stream()
                .map(ApprovalService::toVO)
                .toList();
        return new PageResult<>(list, mapper.count(tenantId, status));
    }

    private static ApprovalRespVO toVO(ApprovalRequestDO item) {
        ApprovalRespVO vo = new ApprovalRespVO();
        BeanUtils.copyProperties(item, vo);
        return vo;
    }

}
