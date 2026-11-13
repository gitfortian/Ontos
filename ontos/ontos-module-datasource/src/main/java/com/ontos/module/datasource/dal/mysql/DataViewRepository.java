package com.ontos.module.datasource.dal.mysql;

import java.util.List;
import java.util.Optional;

import com.ontos.module.datasource.dal.dataobject.DataViewRecord;

/** 数据视图资产仓储（B3）；SQL 只出现在实现类。 */
public interface DataViewRepository {

    Optional<DataViewRecord> find(String id);

    /** 同源同表的 active 视图（重复扫描 = 覆盖更新的定位键）。 */
    Optional<DataViewRecord> findActiveBySource(String sourceConfigId, String sourceSchema,
                                                String sourceTable);

    /** 全部 active 视图，按 id 排序（合成发布快照与校验的稳定序）。 */
    List<DataViewRecord> findAllActive();

    List<DataViewRecord> findAll();

    void insert(DataViewRecord record);

    /** 覆盖更新（重扫描）；返回影响行数。 */
    int updateContent(String id, String name, String columnsJson, String contentHash);

    /** 退役 / 重新激活：仅状态与预期值匹配时生效；返回影响行数。 */
    int updateStatus(String id, String fromStatus, String toStatus);
}
