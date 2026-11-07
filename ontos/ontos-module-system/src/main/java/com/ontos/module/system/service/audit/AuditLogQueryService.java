package com.ontos.module.system.service.audit;

import java.util.List;

import com.ontos.framework.common.pojo.PageResult;
import com.ontos.module.system.controller.admin.audit.vo.AuditLogRespVO;
import com.ontos.module.system.dal.dataobject.AuditLogDO;
import com.ontos.module.system.dal.mysql.AuditLogMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

/**
 * 审计日志查询。写入与查询分离，保证审计表只追加。
 */
@Service
public class AuditLogQueryService {

    private final AuditLogMapper mapper;

    public AuditLogQueryService(AuditLogMapper mapper) {
        this.mapper = mapper;
    }

    public PageResult<AuditLogRespVO> page(String tenantId, String action, Integer pageNo, Integer pageSize) {
        int size = pageSize == null ? 20 : Math.min(Math.max(pageSize, 1), 200);
        int no = pageNo == null ? 1 : Math.max(pageNo, 1);
        int offset = (no - 1) * size;
        List<AuditLogRespVO> list = mapper.selectPage(tenantId, action, size, offset).stream()
                .map(AuditLogQueryService::toVO)
                .toList();
        return new PageResult<>(list, mapper.count(tenantId, action));
    }

    private static AuditLogRespVO toVO(AuditLogDO item) {
        AuditLogRespVO vo = new AuditLogRespVO();
        BeanUtils.copyProperties(item, vo);
        return vo;
    }

}
