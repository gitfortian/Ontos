package com.ontos.module.catalog.dal.mysql;

import java.util.List;
import java.util.Optional;

import com.ontos.module.catalog.dal.dataobject.ScenarioRecord;

/** Scenario 生命周期持久化（管理面）。 */
public interface ScenarioRepository {

    Optional<ScenarioRecord> findById(String id);

    List<ScenarioRecord> findAll();

    void insert(ScenarioRecord record);

    void update(ScenarioRecord record);
}
