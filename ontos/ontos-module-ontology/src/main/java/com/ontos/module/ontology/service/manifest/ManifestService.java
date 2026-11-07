package com.ontos.module.ontology.service.manifest;

import java.util.Optional;

import com.ontos.module.ontology.core.PublishedManifest;

/**
 * 发布清单服务。运行时请求必须携带 manifest_id，且只能命中已加载的清单。
 */
public interface ManifestService {

    /**
     * 获取当前生效清单；未加载时返回 null（只用于健康检查/诊断）。
     */
    PublishedManifest getActiveManifestOrNull();

    /**
     * 校验请求携带的清单 ID 并返回当前生效清单。
     * 未加载、缺失或与生效清单不一致时 fail-closed。
     */
    PublishedManifest validateManifest(String requestedManifestId);

    /**
     * 按 ID 读取历史清单（管理端只读场景）。
     */
    Optional<PublishedManifest> getManifest(String manifestId);

    /**
     * 加载最新发布清单为生效清单。
     */
    void loadActiveManifest();

}
