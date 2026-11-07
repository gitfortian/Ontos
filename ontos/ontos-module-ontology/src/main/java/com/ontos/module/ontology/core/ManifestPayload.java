package com.ontos.module.ontology.core;

import lombok.Data;

/**
 * 发布清单 payload 的结构。payload 原文参与 content_hash 计算，解析结果只用于运行时读取。
 */
@Data
public class ManifestPayload {

    private SemanticManifest semantic;
    private OntologySnapshot ontology;

}
