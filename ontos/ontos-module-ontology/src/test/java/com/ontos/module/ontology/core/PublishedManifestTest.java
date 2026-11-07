package com.ontos.module.ontology.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedManifestTest {

    @Test
    void shouldComputeStableSha256() {
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
                PublishedManifest.sha256("{}"));
    }

    @Test
    void shouldDetectTamperedPayload() {
        PublishedManifest manifest = new PublishedManifest();
        manifest.setPayloadJson("{\"v\":1}");
        manifest.setContentHash(PublishedManifest.sha256("{\"v\":1}"));
        assertTrue(manifest.hashMatches());

        manifest.setPayloadJson("{\"v\":2}");
        assertFalse(manifest.hashMatches(), "载荷被篡改后哈希校验必须失败");
    }

    @Test
    void shouldFailClosedWhenHashMissing() {
        PublishedManifest manifest = new PublishedManifest();
        manifest.setPayloadJson("{}");
        assertFalse(manifest.hashMatches());
    }
}
