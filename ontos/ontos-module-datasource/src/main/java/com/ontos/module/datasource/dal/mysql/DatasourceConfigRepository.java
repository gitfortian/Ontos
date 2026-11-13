package com.ontos.module.datasource.dal.mysql;

import java.util.List;
import java.util.Optional;

import com.ontos.module.datasource.dal.dataobject.DatasourceConfigRecord;

/** 注册数据源配置仓储（B2 Phase 2 前半）；SQL 只出现在实现类。 */
public interface DatasourceConfigRepository {

    Optional<DatasourceConfigRecord> find(String id);

    Optional<DatasourceConfigRecord> findByName(String name);

    /** 全量列表，按 name 排序（管理面低频小表，不分页）。 */
    List<DatasourceConfigRecord> findAll();

    void insert(DatasourceConfigRecord record);

    /** 更新名称/配置；secret 传 null = 保留原密文（编辑时不重填凭据）。返回影响行数。 */
    int update(String id, String name, String configJson, String secret);

    /** 删除；返回影响行数。 */
    int delete(String id);
}
