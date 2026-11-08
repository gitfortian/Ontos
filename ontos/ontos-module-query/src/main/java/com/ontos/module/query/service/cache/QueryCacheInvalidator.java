package com.ontos.module.query.service.cache;

import com.ontos.module.ontology.service.manifest.ManifestActivationListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 清单激活后回收旧版本缓存键（M2/W4/T4.4，ADR-0008 决策 4：异步删除）。
 *
 * <p>异步的理由：发布激活是写路径，不该为一次空间回收等待；且清理失败不影响正确性
 * （键内含 content_hash，旧键本就命中不到）。真正干活的方法自己吞异常并记 WARN。
 */
@Slf4j
@Component
public class QueryCacheInvalidator implements ManifestActivationListener {

    private final QueryCacheService cacheService;

    public QueryCacheInvalidator(QueryCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    @Async("cacheInvalidationExecutor")
    public void afterManifestActivated(String manifestId) {
        log.info("[afterManifestActivated][异步回收旧版本缓存键: {}]", manifestId);
        cacheService.reclaimSuperseded(manifestId);
    }

}
