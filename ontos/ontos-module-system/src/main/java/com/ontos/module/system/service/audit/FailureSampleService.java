package com.ontos.module.system.service.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ontos.module.system.controller.admin.audit.vo.FailureDashboardRespVO;
import com.ontos.module.system.controller.admin.audit.vo.FailureSampleItemVO;
import com.ontos.module.system.controller.admin.audit.vo.FailureSampleRegisterReqVO;
import com.ontos.module.system.dal.dataobject.EvaluationFailureSampleDO;
import com.ontos.module.system.dal.mysql.EvaluationFailureSampleMapper;
import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.system.enums.ErrorCodeConstants.FAILURE_SAMPLE_INVALID;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

/**
 * 失败看板（M6/W5/T5.1，OBL-M7 交付物 ④）：评测失败样本分类落库 + 五维下钻查询。
 *
 * <p>纠正样本回流流程（§3.8）：看板定位失败 → 修复（产品缺陷 / 资产词表 / 题面校准）
 * → 修复题按题集升版流程进回归集（dev 集冒烟/golden）→ 重新评测覆盖。
 * 本表只追加不翻状态——失败史的消除以「新 run 无该样本」表达，失败史可审计。
 */
@Service
public class FailureSampleService {

    /** 单批登记上限（评测批次的失败样本量级远小于此，防线防误用）。 */
    static final int MAX_BATCH_SIZE = 500;

    private final EvaluationFailureSampleMapper mapper;

    public FailureSampleService(EvaluationFailureSampleMapper mapper) {
        this.mapper = mapper;
    }

    /** 批量登记（只追加）；必填校验 fail-closed：setId/runId/questionId/五维缺一即拒。 */
    public int register(String tenantId, FailureSampleRegisterReqVO reqVO) {
        if (reqVO == null || isBlank(reqVO.getSetId()) || isBlank(reqVO.getRunId())) {
            throw exception(FAILURE_SAMPLE_INVALID, "setId / runId 必填");
        }
        List<FailureSampleRegisterReqVO.Item> samples = reqVO.getSamples();
        if (samples == null || samples.isEmpty()) {
            return 0;
        }
        if (samples.size() > MAX_BATCH_SIZE) {
            throw exception(FAILURE_SAMPLE_INVALID, "单批不得超过 " + MAX_BATCH_SIZE + " 条: " + samples.size());
        }
        for (FailureSampleRegisterReqVO.Item item : samples) {
            if (isBlank(item.getQuestionId()) || isBlank(item.getScenarioEntryMode())
                    || isBlank(item.getQueryShape()) || isBlank(item.getTurnType())
                    || item.getIsComposite() == null || isBlank(item.getRiskType())) {
                throw exception(FAILURE_SAMPLE_INVALID,
                        "questionId 与五维（scenario_entry_mode/query_shape/turn_type/is_composite/risk_type）必填: "
                                + item.getQuestionId());
            }
        }
        List<EvaluationFailureSampleDO> records = samples.stream().map(item -> {
            EvaluationFailureSampleDO record = new EvaluationFailureSampleDO();
            record.setSetId(reqVO.getSetId());
            record.setRunId(reqVO.getRunId());
            record.setQuestionId(item.getQuestionId());
            record.setScenarioEntryMode(item.getScenarioEntryMode());
            record.setQueryShape(item.getQueryShape());
            record.setTurnType(item.getTurnType());
            record.setIsComposite(item.getIsComposite());
            record.setRiskType(item.getRiskType());
            record.setExpectedTerminal(item.getExpectedTerminal());
            record.setActualTerminal(item.getActualTerminal());
            record.setErrorCode(item.getErrorCode());
            record.setReason(item.getReason());
            return record;
        }).toList();
        return mapper.insertBatch(tenantId, records);
    }

    /** 五维下钻看板：一次返回全部五维分布（G-5）+ 最近样本明细。 */
    public FailureDashboardRespVO dashboard(String tenantId, String setId, int recentLimit) {
        FailureDashboardRespVO respVO = new FailureDashboardRespVO();
        respVO.setSetId(setId);
        respVO.setTotal(mapper.count(tenantId, setId));
        Map<String, List<Map<String, Object>>> dimensions = new LinkedHashMap<>();
        for (String drill : EvaluationFailureSampleMapper.DRILL_DIMENSIONS) {
            dimensions.put(drill, mapper.drillDown(tenantId, setId, drill));
        }
        respVO.setDimensions(dimensions);
        respVO.setRecentSamples(mapper.selectRecent(tenantId, setId, recentLimit).stream()
                .map(FailureSampleService::toItemVO).toList());
        respVO.setRecentLimit(recentLimit);
        return respVO;
    }

    private static FailureSampleItemVO toItemVO(EvaluationFailureSampleDO item) {
        FailureSampleItemVO vo = new FailureSampleItemVO();
        BeanUtils.copyProperties(item, vo);
        return vo;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

}
