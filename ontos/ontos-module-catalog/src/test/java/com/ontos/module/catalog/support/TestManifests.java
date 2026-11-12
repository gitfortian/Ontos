package com.ontos.module.catalog.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;

/** 测试用生效清单：直接读仓库内 v22 资产，保证草稿校验锚真实数据而不是手搓 payload。 */
public final class TestManifests {

    private TestManifests() {
    }

    public static String v22PayloadJson() {
        try {
            return Files.readString(Path.of("..", "..", "ontos-assets", "first-release-v22.json"));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static PublishedManifest activeV22() {
        String payloadJson = v22PayloadJson();
        // 与 ManifestServiceImpl 装配一致：payloadJson 之外还要有解析后的 semantic / ontology，
        // 否则只读 payloadJson 的校验能过、读结构化字段的 options 会得到空列表。
        ManifestPayload payload = JsonUtils.parseObject(payloadJson, ManifestPayload.class);
        PublishedManifest manifest = new PublishedManifest();
        manifest.setManifestId("2026.11.12-twenty-second");
        manifest.setContentHash("test-content-hash");
        manifest.setPayloadJson(payloadJson);
        manifest.setSemantic(payload.getSemantic());
        manifest.setOntology(payload.getOntology());
        manifest.setRules(payload.getRules());
        return manifest;
    }

    public static ManifestService manifestService(PublishedManifest manifest) {
        return new ManifestService() {
            @Override
            public PublishedManifest getActiveManifestOrNull() {
                return manifest;
            }

            @Override
            public PublishedManifest validateManifest(String requestedManifestId) {
                throw new UnsupportedOperationException("test");
            }

            @Override
            public Optional<PublishedManifest> getManifest(String manifestId) {
                return Optional.empty();
            }

            @Override
            public void loadActiveManifest() {
            }

            @Override
            public PublishedManifest activate(String manifestId) {
                throw new UnsupportedOperationException("test");
            }
        };
    }
}
