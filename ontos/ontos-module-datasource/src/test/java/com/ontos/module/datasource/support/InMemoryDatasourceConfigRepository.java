package com.ontos.module.datasource.support;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.module.datasource.dal.dataobject.DatasourceConfigRecord;
import com.ontos.module.datasource.dal.mysql.DatasourceConfigRepository;

/** 测试内存仓储：与 Jdbc 实现同语义（secret null = 保留原值由 service 层保证，仓储只管覆盖）。 */
public class InMemoryDatasourceConfigRepository implements DatasourceConfigRepository {

    final Map<String, DatasourceConfigRecord> store = new LinkedHashMap<>();

    @Override
    public Optional<DatasourceConfigRecord> find(String id) {
        return Optional.ofNullable(store.get(id)).map(this::copy);
    }

    @Override
    public Optional<DatasourceConfigRecord> findByName(String name) {
        return store.values().stream()
                .filter(record -> record.getName().equals(name))
                .findFirst()
                .map(this::copy);
    }

    @Override
    public List<DatasourceConfigRecord> findAll() {
        List<DatasourceConfigRecord> rows = new ArrayList<>(store.values());
        rows.sort(Comparator.comparing(DatasourceConfigRecord::getName));
        return rows.stream().map(this::copy).toList();
    }

    @Override
    public void insert(DatasourceConfigRecord record) {
        if (record.getId() == null) {
            record.setId("cfg-" + (store.size() + 1));
        }
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(OffsetDateTime.now());
            record.setUpdatedAt(record.getCreatedAt());
        }
        store.put(record.getId(), copy(record));
    }

    @Override
    public int update(String id, String name, String configJson, String secret) {
        DatasourceConfigRecord current = store.get(id);
        if (current == null) {
            return 0;
        }
        current.setName(name);
        current.setConfigJson(configJson);
        if (secret != null) {
            current.setSecret(secret);
        }
        current.setUpdatedAt(OffsetDateTime.now());
        return 1;
    }

    @Override
    public int delete(String id) {
        return store.remove(id) == null ? 0 : 1;
    }

    private DatasourceConfigRecord copy(DatasourceConfigRecord record) {
        DatasourceConfigRecord copy = new DatasourceConfigRecord();
        copy.setId(record.getId());
        copy.setName(record.getName());
        copy.setType(record.getType());
        copy.setConfigJson(record.getConfigJson());
        copy.setSecret(record.getSecret());
        copy.setCreatedBy(record.getCreatedBy());
        copy.setCreatedAt(record.getCreatedAt());
        copy.setUpdatedAt(record.getUpdatedAt());
        return copy;
    }
}
