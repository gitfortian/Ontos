package com.ontos.module.catalog.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.module.catalog.dal.dataobject.SemanticAssetRecord;
import com.ontos.module.catalog.dal.mysql.SemanticAssetRepository;

/**
 * 测试用内存仓储：语义与 Jdbc 实现保持一致（revision 条件更新、状态条件迁移），
 * 只省掉 SQL，不省掉并发与状态判定。
 */
public class InMemorySemanticAssetRepository implements SemanticAssetRepository {

    private final Map<String, SemanticAssetRecord> store = new LinkedHashMap<>();

    @Override
    public Optional<SemanticAssetRecord> find(String assetType, String id) {
        return Optional.ofNullable(store.get(key(assetType, id)));
    }

    @Override
    public List<SemanticAssetRecord> findAll(String assetType, String status, String keyword,
                                             int offset, int limit) {
        List<SemanticAssetRecord> rows = new ArrayList<>();
        for (SemanticAssetRecord record : store.values()) {
            if (!record.getAssetType().equals(assetType)) {
                continue;
            }
            if (status != null && !status.isBlank() && !status.equals(record.getStatus())) {
                continue;
            }
            if (keyword != null && !keyword.isBlank()
                    && !record.getId().contains(keyword) && !record.getName().contains(keyword)) {
                continue;
            }
            rows.add(record);
        }
        int from = Math.min(offset, rows.size());
        int to = Math.min(from + limit, rows.size());
        return new ArrayList<>(rows.subList(from, to));
    }

    @Override
    public long count(String assetType, String status, String keyword) {
        return findAll(assetType, status, keyword, 0, Integer.MAX_VALUE).size();
    }

    @Override
    public void insert(SemanticAssetRecord record) {
        store.put(key(record.getAssetType(), record.getId()), record);
    }

    @Override
    public int updateDraft(SemanticAssetRecord record, long expectedRevision) {
        SemanticAssetRecord current = store.get(key(record.getAssetType(), record.getId()));
        if (current == null || current.getRevision() != expectedRevision
                || !"draft".equals(current.getStatus())) {
            return 0;
        }
        current.setName(record.getName());
        current.setSummary(record.getSummary());
        current.setDefinitionJson(record.getDefinitionJson());
        current.setRevision(current.getRevision() + 1);
        return 1;
    }

    @Override
    public List<SemanticAssetRecord> findAllByStatus(String status) {
        List<SemanticAssetRecord> rows = new ArrayList<>();
        for (SemanticAssetRecord record : store.values()) {
            if (record.getStatus().equals(status)) {
                rows.add(record);
            }
        }
        rows.sort(Comparator.comparing(SemanticAssetRecord::getAssetType)
                .thenComparing(SemanticAssetRecord::getId));
        return rows;
    }

    @Override
    public int markPublished(String assetType, String id, long expectedRevision) {
        SemanticAssetRecord current = store.get(key(assetType, id));
        if (current == null || current.getRevision() != expectedRevision
                || !"reviewing".equals(current.getStatus())) {
            return 0;
        }
        current.setStatus("published");
        current.setPublishedAt(Instant.now());
        current.setRevision(current.getRevision() + 1);
        return 1;
    }

    @Override
    public List<SemanticAssetRecord> findAllPending() {
        List<SemanticAssetRecord> rows = new ArrayList<>();
        for (SemanticAssetRecord record : store.values()) {
            if ("draft".equals(record.getStatus()) || "reviewing".equals(record.getStatus())) {
                rows.add(record);
            }
        }
        rows.sort(Comparator.comparing(SemanticAssetRecord::getAssetType)
                .thenComparing(SemanticAssetRecord::getId));
        return rows;
    }

    @Override
    public int updateStatus(String assetType, String id, String fromStatus, String toStatus,
                            long expectedRevision) {
        SemanticAssetRecord current = store.get(key(assetType, id));
        if (current == null || current.getRevision() != expectedRevision
                || !fromStatus.equals(current.getStatus())) {
            return 0;
        }
        current.setStatus(toStatus);
        current.setRevision(current.getRevision() + 1);
        return 1;
    }

    @Override
    public int reviseToDraft(String assetType, String id, String definitionJson, String name,
                             String summary, long expectedRevision) {
        SemanticAssetRecord current = store.get(key(assetType, id));
        if (current == null || current.getRevision() != expectedRevision
                || !("published".equals(current.getStatus())
                        || "retired".equals(current.getStatus()))) {
            return 0;
        }
        current.setStatus("draft");
        current.setDefinitionJson(definitionJson);
        current.setName(name);
        current.setSummary(summary);
        current.setPublishedAt(null);
        current.setRevision(current.getRevision() + 1);
        return 1;
    }

    @Override
    public long findRevision(String assetType, String id) {
        SemanticAssetRecord record = store.get(key(assetType, id));
        return record == null ? -1L : record.getRevision();
    }

    private static String key(String assetType, String id) {
        return assetType + "|" + id;
    }
}
