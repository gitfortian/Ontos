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
        return dashboard(tenantId, setId, recentLimit, null, null);
    }

    /**
     * 五维下钻看板 + 单维联动（M7/W4/T4.3）：分布始终基于全集，联动只收窄样本明细。
     * 维度必须属于五维白名单，取值按维度类型严格解析（{@code is_composite} 只接受 true/false），
     * 非法维度/取值 fail-closed 拒绝（不静默忽略 → 否则前端会误以为已过滤）。
     */
    public FailureDashboardRespVO dashboard(String tenantId, String setId, int recentLimit,
                                            String drillDimension, String drillValue) {
        FailureDashboardRespVO respVO = new FailureDashboardRespVO();
        respVO.setSetId(setId);
        respVO.setTotal(mapper.count(tenantId, setId));
        Map<String, List<Map<String, Object>>> dimensions = new LinkedHashMap<>();
        for (String drill : EvaluationFailureSampleMapper.DRILL_DIMENSIONS) {
            dimensions.put(drill, mapper.drillDown(tenantId, setId, drill));
        }
        respVO.setDimensions(dimensions);
        boolean filtered = drillDimension != null && !drillDimension.isBlank();
        if (filtered) {
            String dimension = drillDimension.trim();
            // 白名单在 service 层先判：请求参数非法应是 4xx 业务拒绝，不能落到 mapper 的 IllegalArgumentException（500）
            if (!EvaluationFailureSampleMapper.DRILL_DIMENSIONS.contains(dimension)) {
                throw exception(FAILURE_SAMPLE_INVALID, "非法下钻维度: " + drillDimension);
            }
            Object value = drillValue(dimension, drillValue);
            respVO.setDrillDimension(dimension);
            respVO.setDrillValue(String.valueOf(value));
            respVO.setFilteredTotal(mapper.countFiltered(tenantId, setId, dimension, value));
            respVO.setRecentSamples(mapper.selectRecentFiltered(tenantId, setId, dimension, value,
                            recentLimit).stream()
                    .map(FailureSampleService::toItemVO).toList());
        } else {
            respVO.setFilteredTotal(respVO.getTotal());
            respVO.setRecentSamples(mapper.selectRecent(tenantId, setId, recentLimit).stream()
                    .map(FailureSampleService::toItemVO).toList());
        }
        respVO.setRecentLimit(recentLimit);
        return respVO;
    }

    /** 联动取值按维度类型解析：布尔列只接受 true/false（不做 Boolean.parseBoolean 的静默兜底）。 */
    private static Object drillValue(String dimension, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw exception(FAILURE_SAMPLE_INVALID, "联动取值必填: " + dimension);
        }
        if ("is_composite".equals(dimension)) {
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                throw exception(FAILURE_SAMPLE_INVALID,
                        "is_composite 只接受 true/false: " + raw);
            }
            return Boolean.parseBoolean(value);
        }
        return value;
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
