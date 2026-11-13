package com.ontos.module.datasource.service.view;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.datasource.dal.dataobject.DataViewRecord;
import com.ontos.module.datasource.dal.mysql.DataViewRepository;
import com.ontos.module.datasource.service.DataSourceCatalogService;
import com.ontos.module.datasource.service.DataSourceColumnView;
import com.ontos.module.datasource.service.config.DatasourceConfigService;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_CONFIG_NOT_FOUND;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_VIEW_ID_DUPLICATE;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_VIEW_ID_INVALID;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_VIEW_NOT_FOUND;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_VIEW_REACTIVATE_CONFLICT;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_VIEW_REFERENCED;

/**
 * 数据视图资产服务（B3）：扫描固化、漂移检测、退役引用检查。
 *
 * <p>三条铁律：
 * <ol>
 *   <li>**资源事实即时生效**（D1）——扫描/重扫描即时更新 active 视图，校验与建模选择器即刻可见；
 *   运行时确定性由合成发布快照进清单保证，本服务不触碰清单内容；</li>
 *   <li>**扫描连接自持**——列读取经 {@link DataSourceCatalogService}（当前查询库）与
 *   {@link DatasourceConfigService}（注册源，解密重组）完成，插件短连接，零依赖 query 模块；</li>
 *   <li>**退役 fail-closed**——active 清单仍引用的视图不允许退役（R-View 3 的资源侧闸门）；
 *   引用检测兼容迁移期两种口径（旧 `表.列` 与新 `viewId + column`）。</li>
 * </ol>
 */
@Service
public class DataViewService {

    /** 视图 ID 前缀（V23 ck_dv_id_prefix）。 */
    static final String ID_PREFIX = "view.";

    private final DataViewRepository repository;
    private final DataSourceCatalogService catalogService;
    private final DatasourceConfigService configService;
    private final ManifestService manifestService;

    public DataViewService(DataViewRepository repository,
                           DataSourceCatalogService catalogService,
                           DatasourceConfigService configService,
                           ManifestService manifestService) {
        this.repository = repository;
        this.catalogService = catalogService;
        this.configService = configService;
        this.manifestService = manifestService;
    }

    /**
     * 扫描固化：拉列 → 规范化 ID → upsert（同源同表覆盖更新）。返回视图 + 漂移 diff。
     *
     * <p>{@code requestedId} 非空时作为新视图 ID（B3 §3.1「创建时允许手改」）——须匹配
     * {@code view.[a-z0-9_-]+} 且不与既有视图冲突；同源同表覆盖更新路径忽略该参数（ID 不变）。
     */
    public ScanResult scan(String configId, String schema, String table, String name,
                           String requestedId, String actor) {
        List<DataSourceColumnView> columns = fetchColumns(configId, schema, table);
        String hash = hashOf(columns);
        Optional<DataViewRecord> existing = repository.findActiveBySource(configId, schema, table);
        if (existing.isPresent()) {
            DataViewRecord current = existing.get();
            List<String> removed = diffRemoved(current.getColumnsJson(), columns);
            List<String> referencedRemoved = referencedColumns(removed, current.getId(),
                    current.getSourceSchema(), current.getSourceTable());
            repository.updateContent(current.getId(),
                    name != null && !name.isBlank() ? name : current.getName(),
                    JsonUtils.toJsonString(snapshot(columns)), hash);
            return new ScanResult(viewOf(repository.find(current.getId()).orElseThrow()),
                    new Diff(List.of(), removed, List.of(), !hash.equals(current.getContentHash())),
                    referencedRemoved);
        }
        DataViewRecord record = new DataViewRecord();
        record.setId(nextId(table, hash, configId, requestedId));
        record.setName(name != null && !name.isBlank() ? name : table);
        record.setSourceConfigId(configId);
        record.setSourceSchema(schema);
        record.setSourceTable(table);
        record.setColumnsJson(JsonUtils.toJsonString(snapshot(columns)));
        record.setContentHash(hash);
        record.setStatus("active");
        record.setCreatedBy(actor);
        repository.insert(record);
        return new ScanResult(viewOf(repository.find(record.getId()).orElseThrow()),
                new Diff(columns.stream().map(DataSourceColumnView::name).toList(),
                        List.of(), List.of(), true),
                List.of());
    }

    /** 重扫描：定位已有视图 → diff（相对旧快照）→ 覆盖更新 + referencedRemoved 引用检测。 */
    public ScanResult rescan(String id, String actor) {
        DataViewRecord current = require(id);
        List<DataSourceColumnView> columns = fetchColumns(current.getSourceConfigId(),
                current.getSourceSchema(), current.getSourceTable());
        String hash = hashOf(columns);
        List<String> removed = diffRemoved(current.getColumnsJson(), columns);
        List<String> typeChanged = diffTypeChanged(current.getColumnsJson(), columns);
        List<String> referencedRemoved = referencedColumns(removed, current.getId(),
                current.getSourceSchema(), current.getSourceTable());
        repository.updateContent(id, current.getName(),
                JsonUtils.toJsonString(snapshot(columns)), hash);
        return new ScanResult(viewOf(repository.find(id).orElseThrow()),
                new Diff(columns.stream().map(DataSourceColumnView::name)
                                .filter(n -> !oldColumns(current.getColumnsJson()).containsKey(n)).toList(),
                        removed, typeChanged, !hash.equals(current.getContentHash())),
                referencedRemoved);
    }

    /** 退役：active 清单仍引用时 fail-closed（兼容旧「表.列」与新「viewId+column」两种口径）。 */
    public void retire(String id) {
        DataViewRecord current = require(id);
        List<String> referencing = referencingProperties(current);
        if (!referencing.isEmpty()) {
            throw exception(DATASOURCE_VIEW_REFERENCED,
                    id + "（" + referencing.size() + " 个属性）");
        }
        if (repository.updateStatus(id, "active", "retired") == 0) {
            throw exception(DATASOURCE_VIEW_NOT_FOUND, id);
        }
    }

    /** 重新激活：源配置必须仍存在（G3 fail-closed）+ 同源同表不得已有另一个 active 视图。 */
    public void activate(String id) {
        DataViewRecord current = repository.find(id)
                .orElseThrow(() -> exception(DATASOURCE_VIEW_NOT_FOUND, id));
        if (current.getSourceConfigId() != null && !current.getSourceConfigId().isBlank()
                && !configService.configExists(current.getSourceConfigId())) {
            throw exception(DATASOURCE_CONFIG_NOT_FOUND,
                    current.getSourceConfigId() + "（视图 " + id + " 的来源配置已删除，无法重新激活）");
        }
        if (repository.findActiveBySource(current.getSourceConfigId(), current.getSourceSchema(),
                current.getSourceTable()).isPresent()) {
            throw exception(DATASOURCE_VIEW_REACTIVATE_CONFLICT,
                    current.getSourceSchema() + "." + current.getSourceTable());
        }
        if (repository.updateStatus(id, "retired", "active") == 0) {
            throw exception(DATASOURCE_VIEW_NOT_FOUND, id);
        }
    }

    public List<ViewVO> list() {
        return repository.findAll().stream().map(this::viewOf).toList();
    }

    public ViewVO get(String id) {
        return viewOf(require(id));
    }

    // ---------- 内部 ----------

    private List<DataSourceColumnView> fetchColumns(String configId, String schema, String table) {
        return configId == null || configId.isBlank()
                ? catalogService.columns(schema, table)
                : configService.columns(configId, schema, table);
    }

    private DataViewRecord require(String id) {
        return repository.find(id)
                .orElseThrow(() -> exception(DATASOURCE_VIEW_NOT_FOUND, id));
    }

    /** ID 生成规范（B3 §3.1）：view. + 规范化表名（小写/非法字符转下划线/截 40），冲突追加 hash 尾；手改 ID 优先。 */
    private String nextId(String table, String hash, String configId, String requestedId) {
        if (requestedId != null && !requestedId.isBlank()) {
            if (!requestedId.matches("^view\\.[a-z0-9_-]+$")) {
                throw exception(DATASOURCE_VIEW_ID_INVALID, requestedId);
            }
            if (repository.find(requestedId).isPresent()) {
                throw exception(DATASOURCE_VIEW_ID_DUPLICATE, requestedId);
            }
            return requestedId;
        }
        String base = table.toLowerCase().replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (base.length() > 40) {
            base = base.substring(0, 34) + "-" + hash.substring(0, 6);
        }
        String candidate = ID_PREFIX + base;
        if (repository.find(candidate).isEmpty()) {
            return candidate;
        }
        String withHash = ID_PREFIX + (base.length() > 34 ? base.substring(0, 34) : base)
                + "-" + hash.substring(0, 6);
        if (repository.find(withHash).isEmpty()) {
            return withHash;
        }
        throw exception(DATASOURCE_VIEW_ID_DUPLICATE, candidate);
    }

    private static List<Map<String, Object>> snapshot(List<DataSourceColumnView> columns) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (DataSourceColumnView column : columns) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", column.name());
            item.put("type", column.dataType());
            item.put("nullable", column.nullable());
            snapshot.add(item);
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseColumns(String columnsJson) {
        List<Object> raw = JsonUtils.parseObject(columnsJson, List.class);
        List<Map<String, Object>> columns = new ArrayList<>();
        if (raw == null) {
            return columns;
        }
        for (Object item : raw) {
            if (item instanceof Map<?, ?> map) {
                columns.add((Map<String, Object>) map);
            }
        }
        return columns;
    }

    private static Map<String, String> oldColumns(String columnsJson) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (Map<String, Object> column : parseColumns(columnsJson)) {
            byName.put(String.valueOf(column.get("name")),
                    String.valueOf(column.get("type")) + "|" + column.get("nullable"));
        }
        return byName;
    }

    private static List<String> diffRemoved(String oldJson, List<DataSourceColumnView> fresh) {
        Map<String, String> old = oldColumns(oldJson);
        fresh.forEach(column -> old.remove(column.name()));
        return List.copyOf(old.keySet());
    }

    private static List<String> diffTypeChanged(String oldJson, List<DataSourceColumnView> fresh) {
        Map<String, String> old = oldColumns(oldJson);
        List<String> changed = new ArrayList<>();
        for (DataSourceColumnView column : fresh) {
            String previous = old.get(column.name());
            if (previous != null && !previous.equals(column.dataType() + "|" + column.nullable())) {
                changed.add(column.name());
            }
        }
        return changed;
    }

    private static String hashOf(List<DataSourceColumnView> columns) {
        StringBuilder normalized = new StringBuilder();
        columns.stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .forEach(column -> normalized.append(column.name()).append(':')
                        .append(column.dataType()).append(':')
                        .append(column.nullable()).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /**
     * 引用检测（兼容迁移期两口径）：
     * 旧口径 property.column = "表.列" 且 (schema, table) 匹配；新口径 property.viewId = 视图 id。
     */
    private List<String> referencedColumns(List<String> columns, String viewId,
                                           String schema, String table) {
        if (columns.isEmpty()) {
            return List.of();
        }
        List<String> referencing = new ArrayList<>();
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getPayloadJson() == null) {
            return referencing;
        }
        ManifestPayload payload = JsonUtils.parseObject(manifest.getPayloadJson(), ManifestPayload.class);
        OntologySnapshot ontology = payload == null ? null : payload.getOntology();
        if (ontology == null || ontology.getProperties() == null) {
            return referencing;
        }
        // B3 双口径引用检测：新口径 property.viewId + 裸列名；迁移期旧口径 表.列。
        for (OntologySnapshot.Property property : ontology.getProperties()) {
            boolean referencedByViewId = viewId.equals(property.getViewId())
                    && columns.contains(property.getColumn());
            String legacyColumn = property.getColumn() == null ? "" : property.getColumn();
            int dot = legacyColumn.lastIndexOf('.');
            boolean referencedByLegacy = dot > 0
                    && legacyColumn.substring(0, dot).equalsIgnoreCase(table)
                    && columns.contains(legacyColumn.substring(dot + 1));
            if (referencedByViewId || referencedByLegacy) {
                referencing.add(property.getId());
            }
        }
        return referencing;
    }

    /** 退役引用检查：任何口径下仍被引用即拒绝（数量 > 0 即 fail-closed）。 */
    private List<String> referencingProperties(DataViewRecord record) {
        List<String> referencing = new ArrayList<>();
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getPayloadJson() == null) {
            return referencing;
        }
        ManifestPayload payload = JsonUtils.parseObject(manifest.getPayloadJson(), ManifestPayload.class);
        OntologySnapshot ontology = payload == null ? null : payload.getOntology();
        if (ontology == null || ontology.getProperties() == null) {
            return referencing;
        }
        // B3 双口径退役检测：新口径 property.viewId；迁移期旧口径 表.列。
        for (OntologySnapshot.Property property : ontology.getProperties()) {
            if (record.getId().equals(property.getViewId())) {
                referencing.add(property.getId());
                continue;
            }
            String legacyColumn = property.getColumn() == null ? "" : property.getColumn();
            int dot = legacyColumn.lastIndexOf('.');
            if (dot > 0
                    && legacyColumn.substring(0, dot).equalsIgnoreCase(record.getSourceTable())) {
                referencing.add(property.getId());
            }
        }
        return referencing;
    }

    private ViewVO viewOf(DataViewRecord record) {
        return new ViewVO(record.getId(), record.getName(), record.getSourceConfigId(),
                record.getSourceSchema(), record.getSourceTable(),
                JsonUtils.parseObject(record.getColumnsJson(), List.class),
                record.getContentHash(), record.getStatus(), record.getCreatedBy(),
                record.getCreatedAt(), record.getUpdatedAt());
    }

    /** 扫描/重扫描结果：视图 + 漂移 diff + 被引用的消失列。 */
    public record ScanResult(ViewVO view, Diff diff, List<String> referencedRemoved) {
    }

    public record Diff(List<String> addedColumns, List<String> removedColumns,
                       List<String> typeChangedColumns, boolean contentChanged) {
    }

    public record ViewVO(String id, String name, String sourceConfigId, String sourceSchema,
                         String sourceTable, List<Object> columns, String contentHash,
                         String status, String createdBy, java.time.OffsetDateTime createdAt,
                         java.time.OffsetDateTime updatedAt) {
    }
}
