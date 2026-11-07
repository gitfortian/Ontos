package com.ontos.module.system.service.approval;

import java.util.List;
import java.util.Set;

import com.ontos.framework.common.pojo.PageResult;
import com.ontos.module.system.controller.admin.approval.vo.ApprovalRespVO;
import com.ontos.module.system.dal.dataobject.ApprovalRequestDO;
import com.ontos.module.system.dal.mysql.ApprovalRequestMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.system.enums.ErrorCodeConstants.APPROVAL_DECISION_INVALID;
import static com.ontos.module.system.enums.ErrorCodeConstants.APPROVAL_NOT_FOUND;

/**
 * 审批编排：列表按租户隔离，决策人取自认证上下文，且仅允许处理 PENDING 单据。
 */
@Service
public class ApprovalService {

    private static final Set<String> VALID_DECISIONS = Set.of("APPROVED", "REJECTED");

    private final ApprovalRequestMapper mapper;

    public ApprovalService(ApprovalRequestMapper mapper) {
        this.mapper = mapper;
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

    public void decide(String id, String tenantId, String decision, String reason, String decidedBy) {
        if (!VALID_DECISIONS.contains(decision)) {
            throw exception(APPROVAL_DECISION_INVALID);
        }
        if (mapper.decide(id, tenantId, decision, reason, decidedBy) != 1) {
            throw exception(APPROVAL_NOT_FOUND);
        }
    }

    private static ApprovalRespVO toVO(ApprovalRequestDO item) {
        ApprovalRespVO vo = new ApprovalRespVO();
        BeanUtils.copyProperties(item, vo);
        return vo;
    }

}
