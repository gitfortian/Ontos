package com.ontos.module.ontology.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

import lombok.Data;

/**
 * 内存中的发布清单：版本组合 + 已解析的语义/本体视图。
 * 同一 manifest_id 的内容永不变更；运行时请求固定到单一清单。
 */
@Data
public class PublishedManifest {

    private String manifestId;
    private String contentHash;
    private String ontologyVersion;
    private String semanticVersion;
    private String policyVersion;
    private String ruleVersion;
    private String timeParserVersion;
    private String copyTemplateVersion;
    private String payloadJson;
    private SemanticManifest semantic;
    private OntologySnapshot ontology;
    private OntologyGraph ontologyGraph;
    private LocalDateTime createdAt;

    public boolean hashMatches() {
        return contentHash != null && contentHash.equalsIgnoreCase(sha256(payloadJson));
    }

    public static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

}
