package com.ontos.module.system.controller.admin.audit.vo;

import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * 失败看板（M6/W5/T5.1，G-5 五维下钻）：
 * {@code scenario_entry_mode / query_shape / turn_type / is_composite / risk_type}
 * 五个维度的失败分布 + 最近样本明细。
 */
@Data
public class FailureDashboardRespVO {

    private String setId;

    private long total;

    /** value → count 降序（每维）。 */
    private Map<String, List<Map<String, Object>>> dimensions;

    private List<FailureSampleItemVO> recentSamples;

    private Integer recentLimit;

}
