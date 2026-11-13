package com.ontos.module.catalog.service.asset;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.catalog.core.CatalogAssetStatus;
import com.ontos.module.catalog.core.SemanticAssetType;
import com.ontos.module.catalog.dal.dataobject.SemanticAssetRecord;
import com.ontos.module.catalog.dal.mysql.SemanticAssetRepository;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.ontology.service.publish.PublishService;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_REVISION_CONFLICT;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_SYNTHESIS_EMPTY;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_SYNTHESIS_NO_ACTIVE_MANIFEST;
import static com.ontos.module.catalog.enums.ErrorCodeConstants.ASSET_SYNTHESIS_REJECTED;

/**
 * 合成发布（B1/P1-B）：把全部 reviewing 资产合并进当前生效清单，走既有发布链路
 * （dry-run 预检 → 确认发布 → 激活），再把批次落定为 published。
 *
 * <p>三条铁律：
 * <ol>
 *   <li>复用 {@link PublishService}——合成绝不绕过未知字段检测、结构/发布形态校验与 VQR 失效分析；</li>
 *   <li>成批校验——合并后的整包清单做全量校验，新对象 + 挂在其上的属性这类跨草稿引用在此才成立；</li>
 *   <li>落定顺序 = 发布 → 激活 → 标记 published：清单生效是事实源，资产状态是回执；
 *       标记失败（并发修订）以 ASSET_REVISION_CONFLICT 显式报错，不静默吞掉。</li>
 * </ol>
 */
@Service
public class SemanticAssetSynthesisService {

    static final String SOURCE_REF = "catalog-synthesis";
    private static final DateTimeFormatter ID_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final int MAX_ID_ATTEMPTS = 10;

    private static final String[] ONES = {
        "", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth",
        "ninth", "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth",
        "sixteenth", "seventeenth", "eighteenth", "nineteenth",
    };
    private static final String[] TENS = {
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    };

    private final SemanticAssetRepository repository;
    private final ManifestService manifestService;
    private final PublishService publishService;
    /** 资源侧 active 视图供给（B3）：合成时快照进新清单（D1：资源事实即时生效，发布锚定快照）。 */
    private final com.ontos.module.ontology.service.dataview.DataViewSnapshotProvider viewProvider;

    public SemanticAssetSynthesisService(SemanticAssetRepository repository,
                                         ManifestService manifestService,
                                         PublishService publishService,
                                         com.ontos.module.ontology.service.dataview.DataViewSnapshotProvider viewProvider) {
        this.repository = repository;
        this.manifestService = manifestService;
        this.publishService = publishService;
        this.viewProvider = viewProvider == null ? List::of : viewProvider;
    }

    public SemanticAssetSynthesisReport synthesize() {
        List<SemanticAssetRecord> reviewing = repository.findAllByStatus(
                CatalogAssetStatus.REVIEWING.code());
        if (reviewing.isEmpty()) {
            throw exception(ASSET_SYNTHESIS_EMPTY);
        }
        PublishedManifest active = manifestService.getActiveManifestOrNull();
        if (active == null || active.getPayloadJson() == null || active.getPayloadJson().isBlank()) {
            throw exception(ASSET_SYNTHESIS_NO_ACTIVE_MANIFEST);
        }
        Map<String, Object> payload = JsonUtils.parseObject(active.getPayloadJson(), Map.class);
        // B3：数据视图快照进新清单（D1：资源事实即时生效，发布锚定快照）——upsert 前写入
        payload.put("dataViews", viewProvider.activeViews());
        for (SemanticAssetRecord record : reviewing) {
            upsert(payload, record);
        }

        String prefix = LocalDate.now().format(ID_DATE) + "-";
        int base = 1 + countTodayRecords(prefix);
        for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
            String manifestId = prefix + ordinalWords(base + attempt);
            PublishService.PublishReportVO probe = publishService.publish(
                    new PublishService.PublishRequest(manifestId, SOURCE_REF, true, payload));
            if (probe.alreadyExists()) {
                continue;
            }
            if (!probe.valid()) {
                throw exception(ASSET_SYNTHESIS_REJECTED, String.join("；", probe.violations()));
            }
            PublishService.PublishReportVO report = publishService.publish(
                    new PublishService.PublishRequest(manifestId, SOURCE_REF, false, payload));
            manifestService.activate(report.manifestId());
            List<SemanticAssetRecord> published = markPublished(reviewing);
            return new SemanticAssetSynthesisReport(
                    report.manifestId(), report.contentHash(), active.getManifestId(),
                    published.stream()
                            .map(record -> new SemanticAssetSynthesisReport.AssetRef(
                                    record.getAssetType(), record.getId()))
                            .toList(),
                    report.assetCounts(), report.invalidatedVqrs());
        }
        throw exception(ASSET_SYNTHESIS_REJECTED, "连续 " + MAX_ID_ATTEMPTS + " 个 manifestId 均已占用");
    }

    /** 草稿定义按类型 upsert 进清单段；同 ID 覆盖（草稿对生效资产的演进的语义）。 */
    @SuppressWarnings("unchecked")
    private static void upsert(Map<String, Object> payload, SemanticAssetRecord record) {
        SemanticAssetType type = SemanticAssetType.of(record.getAssetType());
        String section = switch (type) {
            case CONCEPT, PROPERTY, RELATION -> "ontology";
            case METRIC, DIMENSION -> "semantic";
        };
        String listKey = switch (type) {
            case CONCEPT -> "concepts";
            case PROPERTY -> "properties";
            case RELATION -> "relations";
            case METRIC -> "metrics";
            case DIMENSION -> "dimensions";
        };
        Map<String, Object> definition = JsonUtils.parseObject(record.getDefinitionJson(), Map.class);
        if (definition == null || definition.get("id") == null) {
            throw exception(ASSET_SYNTHESIS_REJECTED,
                    "资产定义缺少 id: " + record.getAssetType() + "/" + record.getId());
        }
        Map<String, Object> root = (Map<String, Object>) payload.computeIfAbsent(
                section, key -> new LinkedHashMap<String, Object>());
        List<Object> items = (List<Object>) root.computeIfAbsent(listKey, key -> new ArrayList<Object>());
        String id = String.valueOf(definition.get("id"));
        items.removeIf(item -> id.equals(String.valueOf(
                ((Map<String, Object>) item).get("id"))));
        items.add(definition);
    }

    /** 清单生效后落定资产状态；0 行 = 并发修订，显式冲突，不静默。 */
    private List<SemanticAssetRecord> markPublished(List<SemanticAssetRecord> reviewing) {
        List<SemanticAssetRecord> published = new ArrayList<>();
        for (SemanticAssetRecord record : reviewing) {
            int rows = repository.markPublished(
                    record.getAssetType(), record.getId(), record.getRevision());
            if (rows == 0) {
                long current = repository.findRevision(record.getAssetType(), record.getId());
                throw exception(ASSET_REVISION_CONFLICT, record.getRevision(), current);
            }
            published.add(record);
        }
        return published;
    }

    /** manifestId 顺序基数：当天已有发布记录数 + 1；遇占用由调用方递增重试。 */
    private int countTodayRecords(String prefix) {
        return (int) publishService.listPublishRecords(1000).stream()
                .map(row -> String.valueOf(row.get("manifestId")))
                .filter(id -> id.startsWith(prefix))
                .count();
    }

    /** 序数词：first…nineteenth、twenty-first…ninety-nine；≥100 回退 v{n}。 */
    static String ordinalWords(int number) {
        if (number < 1 || number > 99) {
            return "v" + number;
        }
        if (number < 20) {
            return ONES[number];
        }
        int tens = number / 10;
        int ones = number % 10;
        return ones == 0 ? TENS[tens] : TENS[tens] + "-" + ONES[ones];
    }
}
