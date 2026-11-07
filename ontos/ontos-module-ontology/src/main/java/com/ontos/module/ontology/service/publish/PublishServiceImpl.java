package com.ontos.module.ontology.service.publish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.ManifestStructureValidator;
import com.ontos.module.ontology.core.OntologyPublishValidator;
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
    private final ManifestStructureValidator structureValidator = new ManifestStructureValidator();
    private final OntologyPublishValidator publishValidator = new OntologyPublishValidator();

    public PublishServiceImpl(PublishedSnapshotRepository repository, PublishGuard publishGuard) {
        this.repository = repository;
        this.publishGuard = publishGuard;
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

        structureValidator.validate(payload).forEach(v -> violations.add(v.toString()));
        publishValidator.validate(payload.getOntology()).forEach(v -> violations.add(v.toString()));
        boolean alreadyExists = repository.selectByManifestId(request.manifestId()).isPresent();

        if (request.dryRun()) {
            repository.insertPublishRecord(request.manifestId(), existingHashOrDryRun(request.manifestId()),
                    true, request.sourceRef(), publisher);
            log.info("[publish][dry-run] manifestId({}) violations({}) alreadyExists({})",
                    request.manifestId(), violations.size(), alreadyExists);
            return new PublishReportVO(request.manifestId(), violations.isEmpty() && !alreadyExists,
                    violations, alreadyExists, null, assetCounts(payload));
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
                assetCounts(payload));
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
        counts.put("scenarios", size(payload.getScenarios()));
        return counts;
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

}
