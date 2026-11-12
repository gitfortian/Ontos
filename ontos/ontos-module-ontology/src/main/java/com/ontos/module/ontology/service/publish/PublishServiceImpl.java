package com.ontos.module.ontology.service.publish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.ManifestStructureValidator;
import com.ontos.module.ontology.core.OntologyPublishValidator;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.VqrInvalidationAnalyzer;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.ontology.dal.mysql.PublishedSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.MANIFEST_ALREADY_EXISTS;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.PUBLISH_PAYLOAD_INVALID;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.PUBLISH_VALIDATION_FAILED;

/**
 * 发布服务实现：预检与确认发布共用同一套校验（结构 + 图语义），确认发布才写清单。
 *
 * <p>关键口径：content_hash 由 PG 按 jsonb 规范文本计算（仓库层 SQL），发布后按同一文本
 * 读取校验——键序/空白差异不进哈希，PoC #2 的可复现口径不因发布通道改变。
 * 策略闸门经 {@link PublishGuard} 端口注入（实现位于装配点），保持 fail-closed：
 * M4 策略快照落地前，非 local profile 下本服务整体 403。
 */
@Slf4j
@Service
public class PublishServiceImpl implements PublishService {

    private final PublishedSnapshotRepository repository;
    private final PublishGuard publishGuard;
    private final ManifestService manifestService;
    private final ManifestStructureValidator structureValidator = new ManifestStructureValidator();
    private final OntologyPublishValidator publishValidator = new OntologyPublishValidator();
    private final VqrInvalidationAnalyzer vqrInvalidationAnalyzer = new VqrInvalidationAnalyzer();

    public PublishServiceImpl(PublishedSnapshotRepository repository, PublishGuard publishGuard,
                              ManifestService manifestService) {
        this.repository = repository;
        this.publishGuard = publishGuard;
        this.manifestService = manifestService;
    }

    @Override
    @Transactional
    public PublishReportVO publish(PublishRequest request) {
        if (request == null || request.payload() == null
                || request.manifestId() == null || request.manifestId().isBlank()) {
            throw exception(PUBLISH_PAYLOAD_INVALID);
        }
        // 策略闸门：本地 dev-allow-all 放行；生产在策略快照落地前一律 403（fail-closed）
        publishGuard.requirePublishAllowed("ontology.publish");
        String publisher = publishGuard.currentPublisher();

        // 未知字段策略（fail-closed）：提交树与「类型化回装树」做键差分——拼写错误的字段
        // 若被 Jackson 静默丢弃，发布出去的清单就和提交的不一致，这里显式拒绝
        List<String> violations = new ArrayList<>();
        ManifestPayload payload = JsonUtils.parseObject(JsonUtils.toJsonString(request.payload()),
                ManifestPayload.class);
        Map<String, Object> typedRoundTrip = payload == null ? null
                : JsonUtils.parseObject(JsonUtils.toJsonString(payload), Map.class);
        if (typedRoundTrip == null) {
            throw exception(PUBLISH_PAYLOAD_INVALID);
        }
        List<String> unknownFields = new ArrayList<>();
        collectUnknownFields(request.payload(), typedRoundTrip, "", unknownFields);
        unknownFields.forEach(field -> violations.add(
                new ManifestStructureValidator.Violation("payload." + field,
                        "未知字段（清单结构没有该键，疑似拼写错误）：发布将静默丢弃，请修正或删除").toString()));

        // M6/W3/T3.4（§11.3 / F-7）：VQR 依赖影响分析——对照当前生效清单，命中失效条件的
        // VQR 从新清单移除（失效经发布流水线生效；不静默——报告随发布响应透出）。
        // 移除先于结构校验：本次升级有意废弃的资产不应被残留 VQR 卡住整个发布。
        PublishedManifest activeManifest = manifestService.getActiveManifestOrNull();
        ManifestPayload oldPayload = activeManifest == null || activeManifest.getPayloadJson() == null
                ? null : JsonUtils.parseObject(activeManifest.getPayloadJson(), ManifestPayload.class);
        List<VqrInvalidationAnalyzer.Invalidation> invalidatedVqrs =
                vqrInvalidationAnalyzer.analyze(oldPayload, payload);
        if (!invalidatedVqrs.isEmpty()) {
            // 失效生效 = 移除 VQR + 级联清理场景引用（T34-8：悬空引用会把后续发布卡死）
            int cascadeRemoved = vqrInvalidationAnalyzer.apply(payload, invalidatedVqrs);
            invalidatedVqrs.forEach(invalidation -> log.info(
                    "[publish][VQR 自动失效] vqr({}) reason({})", invalidation.vqrId(),
                    invalidation.reason()));
            log.info("[publish][VQR 失效级联清理] scenario 引用移除 {} 条", cascadeRemoved);
        }

        structureValidator.validate(payload).forEach(v -> violations.add(v.toString()));
        // B0 §3.3.3 发布端形态闸门：新发布只收 v20 语义形态。刻意不放进通用 validate()——
        // 激活/回滚走 ManifestServiceImpl.assemble，必须继续接受 v19 历史快照。
        structureValidator.validatePublishBindingForm(payload).forEach(v -> violations.add(v.toString()));
        publishValidator.validate(payload.getOntology()).forEach(v -> violations.add(v.toString()));
        boolean alreadyExists = repository.selectByManifestId(request.manifestId()).isPresent();

        if (request.dryRun()) {
            repository.insertPublishRecord(request.manifestId(), existingHashOrDryRun(request.manifestId()),
                    true, request.sourceRef(), publisher);
            log.info("[publish][dry-run] manifestId({}) violations({}) alreadyExists({})",
                    request.manifestId(), violations.size(), alreadyExists);
            return new PublishReportVO(request.manifestId(), violations.isEmpty() && !alreadyExists,
                    violations, alreadyExists, null, assetCounts(payload),
                    invalidatedVqrs.stream().map(i -> i.vqrId() + ": " + i.reason()).toList());
        }

        if (alreadyExists) {
            throw exception(MANIFEST_ALREADY_EXISTS, request.manifestId());
        }
        if (!violations.isEmpty()) {
            throw exception(PUBLISH_VALIDATION_FAILED, violations.toString());
        }
        String payloadJson = JsonUtils.toJsonString(payload);
        String contentHash = repository.insertSnapshot(request.manifestId(), payloadJson, publisher);
        repository.insertPublishRecord(request.manifestId(), contentHash, false, request.sourceRef(), publisher);
        log.info("[publish][确认发布] manifestId({}) contentHash({}) publisher({})",
                request.manifestId(), contentHash, publisher);
        return new PublishReportVO(request.manifestId(), true, List.of(), false, contentHash,
                assetCounts(payload),
                invalidatedVqrs.stream().map(i -> i.vqrId() + ": " + i.reason()).toList());
    }

    /** 递归键差分：提交树里有、类型化回装树里没有的键 = 未知字段（会被丢弃）。 */
    @SuppressWarnings("unchecked")
    private static void collectUnknownFields(Object raw, Object typed, String path, List<String> out) {
        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, Object> typedMap = typed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!typedMap.containsKey(key)) {
                    out.add(path + key);
                } else {
                    collectUnknownFields(entry.getValue(), typedMap.get(key), path + key + ".", out);
                }
            }
        } else if (raw instanceof List<?> rawList && typed instanceof List<?> typedList) {
            for (int i = 0; i < rawList.size(); i++) {
                collectUnknownFields(rawList.get(i),
                        i < typedList.size() ? typedList.get(i) : null, path + i + ".", out);
            }
        }
    }

    private String existingHashOrDryRun(String manifestId) {
        return repository.selectByManifestId(manifestId)
                .map(snapshot -> snapshot.getContentHash())
                .orElse("dry-run");
    }

    private Map<String, Integer> assetCounts(ManifestPayload payload) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (payload.getSemantic() != null) {
            counts.put("metrics", size(payload.getSemantic().getMetrics()));
            counts.put("dimensions", size(payload.getSemantic().getDimensions()));
        }
        if (payload.getOntology() != null) {
            counts.put("concepts", size(payload.getOntology().getConcepts()));
            counts.put("properties", size(payload.getOntology().getProperties()));
            counts.put("relations", size(payload.getOntology().getRelations()));
            counts.put("events", size(payload.getOntology().getEvents()));
        }
        counts.put("rules", size(payload.getRules()));
        counts.put("skills", size(payload.getSkills()));
        counts.put("vqrs", size(payload.getVqrs()));
        counts.put("scenarios", size(payload.getScenarios()));
        return counts;
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    @Override
    public List<Map<String, Object>> listPublishRecords(int limit) {
        return repository.selectPublishRecords(Math.max(1, Math.min(limit, 200)));
    }

}
