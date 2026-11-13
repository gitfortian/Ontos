package com.ontos.module.datasource.service.view;

import java.util.List;
import java.util.Map;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.datasource.dal.mysql.DataViewRepository;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.service.dataview.DataViewSnapshotProvider;
import org.springframework.stereotype.Component;

/**
 * 数据视图快照供给实现（B3）：读 data_view 表 active 全量 → 清单 DataView 结构。
 * 只做结构与映射，不做校验（校验在语义侧 R-View）。
 */
@Component
public class DataViewSnapshotProviderImpl implements DataViewSnapshotProvider {

    private final DataViewRepository repository;

    public DataViewSnapshotProviderImpl(DataViewRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ManifestPayload.DataView> activeViews() {
        return repository.findAllActive().stream().map(record -> {
            ManifestPayload.DataView view = new ManifestPayload.DataView();
            view.setId(record.getId());
            view.setName(record.getName());
            view.setSchema(record.getSourceSchema());
            view.setTable(record.getSourceTable());
            List<ManifestPayload.DataView.Column> columns = new java.util.ArrayList<>();
            for (Object item : com.ontos.framework.common.util.json.JsonUtils
                    .parseObject(record.getColumnsJson(), List.class)) {
                if (item instanceof Map<?, ?> map) {
                    ManifestPayload.DataView.Column column = new ManifestPayload.DataView.Column();
                    column.setName(String.valueOf(map.get("name")));
                    column.setType(String.valueOf(map.get("type")));
                    column.setNullable(Boolean.parseBoolean(String.valueOf(map.get("nullable"))));
                    columns.add(column);
                }
            }
            view.setColumns(columns);
            view.setContentHash(record.getContentHash());
            return view;
        }).toList();
    }
}
