package com.ontos.module.catalog.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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
        PublishedManifest manifest = new PublishedManifest();
        manifest.setManifestId("2026.11.12-twenty-second");
        manifest.setContentHash("test-content-hash");
        manifest.setPayloadJson(v22PayloadJson());
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
