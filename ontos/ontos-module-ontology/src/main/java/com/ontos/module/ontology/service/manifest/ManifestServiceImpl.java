package com.ontos.module.ontology.service.manifest;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.OntologyGraph;
import com.ontos.module.ontology.core.OntologyPublishValidator;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.dal.dataobject.PublishedSnapshotDO;
import com.ontos.module.ontology.dal.mysql.PublishedSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.MANIFEST_HASH_MISMATCH;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.MANIFEST_MISMATCH;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.MANIFEST_NOT_FOUND;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.ONTOLOGY_INVALID;

@Slf4j
@Service
public class ManifestServiceImpl implements ManifestService, ApplicationRunner {

    private final PublishedSnapshotRepository snapshotRepository;
    private final ManifestProperties properties;
    private final OntologyPublishValidator publishValidator = new OntologyPublishValidator();
    private final AtomicReference<PublishedManifest> active = new AtomicReference<>();

    public ManifestServiceImpl(PublishedSnapshotRepository snapshotRepository, ManifestProperties properties) {
        this.snapshotRepository = snapshotRepository;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isStartupLoad()) {
            return;
        }
        try {
            loadActiveManifest();
        } catch (ServiceException ex) {
            if (isIntegrityViolation(ex)) {
                // 哈希不匹配 / 清单结构非法属于完整性违规：带病清单绝不允许静默上线，
                // 副本直接拒绝启动（技术选型 §1.2.1「加载失败的副本 fail-closed 拒绝服务」）。
                throw new IllegalStateException(
                        "发布清单完整性校验失败，拒绝启动: " + ex.getMessage(), ex);
            }
            // 可用性缺失（清单尚未发布 / 数据库暂不可达）：保持启动，业务入口 fail-closed
            //（见 validateManifest），本地无库开发与空库首启不因此被阻塞。
            log.error("[run][发布清单暂不可用，业务接口将 fail-closed: {}]", ex.getMessage());
        }
    }

    private static boolean isIntegrityViolation(ServiceException ex) {
        Integer code = ex.getCode();
        return MANIFEST_HASH_MISMATCH.getCode().equals(code) || ONTOLOGY_INVALID.getCode().equals(code);
    }

    @Override
    public PublishedManifest getActiveManifestOrNull() {
        return active.get();
    }

    @Override
    public PublishedManifest validateManifest(String requestedManifestId) {
        if (requestedManifestId == null || requestedManifestId.isBlank()) {
            throw invalidParamException("缺少 manifest_id");
        }
        PublishedManifest manifest = active.get();
        if (manifest == null) {
            throw exception(MANIFEST_NOT_FOUND);
        }
        if (!manifest.getManifestId().equals(requestedManifestId)) {
            throw exception(MANIFEST_MISMATCH, requestedManifestId);
        }
        return manifest;
    }

    @Override
    public Optional<PublishedManifest> getManifest(String manifestId) {
        return snapshotRepository.selectByManifestId(manifestId).map(this::assemble);
    }

    @Override
    public void loadActiveManifest() {
        PublishedSnapshotDO snapshot = snapshotRepository.selectLatest()
                .orElseThrow(() -> exception(MANIFEST_NOT_FOUND));
        PublishedManifest manifest = assemble(snapshot);
        active.set(manifest);
        log.info("[loadActiveManifest][manifestId({}) contentHash({})]", manifest.getManifestId(),
                manifest.getContentHash());
    }

    private PublishedManifest assemble(PublishedSnapshotDO snapshot) {
        if (!snapshot.getContentHash().equalsIgnoreCase(PublishedManifest.sha256(snapshot.getPayload()))) {
            throw exception(MANIFEST_HASH_MISMATCH, snapshot.getManifestId());
        }
        ManifestPayload payload = JsonUtils.parseObject(snapshot.getPayload(), ManifestPayload.class);
        if (payload == null || payload.getSemantic() == null || payload.getOntology() == null) {
            throw exception(ONTOLOGY_INVALID, "payload 缺少 semantic 或 ontology");
        }
        var violations = publishValidator.validate(payload.getOntology());
        if (!violations.isEmpty()) {
            throw exception(ONTOLOGY_INVALID, violations.toString());
        }

        PublishedManifest manifest = new PublishedManifest();
        manifest.setManifestId(snapshot.getManifestId());
        manifest.setContentHash(snapshot.getContentHash());
        manifest.setOntologyVersion(snapshot.getOntologyVersion());
        manifest.setSemanticVersion(snapshot.getSemanticVersion());
        manifest.setPolicyVersion(snapshot.getPolicyVersion());
        manifest.setRuleVersion(snapshot.getRuleVersion());
        manifest.setTimeParserVersion(snapshot.getTimeParserVersion());
        manifest.setCopyTemplateVersion(snapshot.getCopyTemplateVersion());
        manifest.setPayloadJson(snapshot.getPayload());
        manifest.setSemantic(payload.getSemantic());
        manifest.setOntology(payload.getOntology());
        manifest.setOntologyGraph(new OntologyGraph(payload.getOntology()));
        manifest.setCreatedAt(snapshot.getCreatedAt());
        return manifest;
    }

}
