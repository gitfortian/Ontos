package com.ontos.module.datasource.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.module.datasource.dal.dataobject.DataViewRecord;
import com.ontos.module.datasource.dal.mysql.DataViewRepository;

/** 数据视图内存仓储：与 Jdbc 实现同语义（含同源同表 active 唯一定位）。 */
public class InMemoryDataViewRepository implements DataViewRepository {

    final Map<String, DataViewRecord> store = new LinkedHashMap<>();

    @Override
    public Optional<DataViewRecord> find(String id) {
        return Optional.ofNullable(store.get(id)).map(this::copy);
    }

    @Override
    public Optional<DataViewRecord> findActiveBySource(String sourceConfigId, String sourceSchema,
                                                       String sourceTable) {
        return store.values().stream()
                .filter(record -> "active".equals(record.getStatus()))
                .filter(record -> java.util.Objects.equals(record.getSourceConfigId(), sourceConfigId))
                .filter(record -> record.getSourceSchema().equals(sourceSchema))
                .filter(record -> record.getSourceTable().equals(sourceTable))
                .findFirst()
                .map(this::copy);
    }

    @Override
    public List<DataViewRecord> findAllActive() {
        return store.values().stream()
                .filter(record -> "active".equals(record.getStatus()))
                .sorted(Comparator.comparing(DataViewRecord::getId))
                .map(this::copy).toList();
    }

    @Override
    public List<DataViewRecord> findAllActiveByConfig(String sourceConfigId) {
        return store.values().stream()
                .filter(record -> "active".equals(record.getStatus()))
                .filter(record -> sourceConfigId != null
                        && sourceConfigId.equals(record.getSourceConfigId()))
                .sorted(java.util.Comparator.comparing(DataViewRecord::getId))
                .toList();
    }

    @Override
    public List<DataViewRecord> findAll() {
        List<DataViewRecord> rows = new ArrayList<>(store.values());
        rows.sort(Comparator.comparing(DataViewRecord::getId));
        return rows.stream().map(this::copy).toList();
    }

    @Override
    public void insert(DataViewRecord record) {
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(java.time.OffsetDateTime.now());
            record.setUpdatedAt(record.getCreatedAt());
        }
        store.put(record.getId(), copy(record));
    }

    @Override
    public int updateContent(String id, String name, String columnsJson, String contentHash) {
        DataViewRecord current = store.get(id);
        if (current == null) {
            return 0;
        }
        current.setName(name);
        current.setColumnsJson(columnsJson);
        current.setContentHash(contentHash);
        current.setUpdatedAt(java.time.OffsetDateTime.now());
        return 1;
    }

    @Override
    public int updateStatus(String id, String fromStatus, String toStatus) {
        DataViewRecord current = store.get(id);
        if (current == null || !current.getStatus().equals(fromStatus)) {
            return 0;
        }
        current.setStatus(toStatus);
        current.setUpdatedAt(java.time.OffsetDateTime.now());
        return 1;
    }

    private DataViewRecord copy(DataViewRecord record) {
        DataViewRecord copy = new DataViewRecord();
        copy.setId(record.getId());
        copy.setName(record.getName());
        copy.setSourceConfigId(record.getSourceConfigId());
        copy.setSourceSchema(record.getSourceSchema());
        copy.setSourceTable(record.getSourceTable());
        copy.setColumnsJson(record.getColumnsJson());
        copy.setContentHash(record.getContentHash());
        copy.setStatus(record.getStatus());
        copy.setCreatedBy(record.getCreatedBy());
        copy.setCreatedAt(record.getCreatedAt());
        copy.setUpdatedAt(record.getUpdatedAt());
        return copy;
    }
}
