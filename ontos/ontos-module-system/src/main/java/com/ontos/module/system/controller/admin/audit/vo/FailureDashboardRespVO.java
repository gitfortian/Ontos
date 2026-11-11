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

    /** 当前生效的单维联动维度（为空 = 未过滤；分布始终基于全集）。 */
    private String drillDimension;

    /** 当前生效的联动取值（与 drillDimension 同时出现）。 */
    private String drillValue;

    /** 联动过滤命中条数（未过滤时等于 total）。 */
    private long filteredTotal;

}
