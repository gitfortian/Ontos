package com.ontos.module.query.service.cache;

import java.util.List;
import java.util.Optional;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.query.core.QueryProperties;
import com.ontos.module.query.core.executor.QueryResult;
import com.ontos.module.query.dal.dataobject.QueryCacheDO;
import com.ontos.module.query.dal.mysql.QueryCacheRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 查询结果缓存读写（M2/W4/T4.4，ADR-0008）。
 *
 * <p>语义边界（ADR-0008 决策 3）：缓存是可用性优化，**不是合规边界**。因此
 * 读失败按未命中继续走完整链路、写失败只记 WARN，都不影响主响应——
 * 与「审计写入失败即整单失败」是两套口径，不要混用。
 *
 * <p>正确性不依赖失效任务：键内已含 {@code content_hash} 与全部版本号，
 * 清单升级后旧键天然失配；清理只做空间回收。
 */
@Slf4j
@Service
public class QueryCacheService {

    private final QueryCacheRepository repository;
    private final QueryProperties properties;

    public QueryCacheService(QueryCacheRepository repository, QueryProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isCacheEnabled();
    }

    /** 命中返回结果（cached=true），未命中/读失败/解析失败一律返回空，由调用方走完整链路。 */
    public Optional<QueryResult> get(String cacheKey) {
        try {
            Optional<QueryCacheDO> row = repository.selectByKey(cacheKey);
            if (row.isEmpty()) {
                return Optional.empty();
            }
            QueryResult result = JsonUtils.parseObject(row.get().getResultJson(), QueryResult.class);
            if (result == null) {
                log.warn("[get][缓存体为空，按未命中处理] {}", cacheKey);
                return Optional.empty();
            }
            result.setCached(true);
            repository.touch(cacheKey);
            return Optional.of(result);
        } catch (RuntimeException ex) {
            log.warn("[get][缓存读取失败，按未命中处理: {}]", ex.getMessage());
            return Optional.empty();
        }
    }

    /** 回写：失败只降级 WARN（ADR-0008 决策 3），绝不影响已经拿到的结果。 */
    public void put(String cacheKey, String manifestId, String contentHash, String queryShape,
                    QueryResult result) {
        try {
            repository.upsert(cacheKey, manifestId, contentHash, queryShape,
                    JsonUtils.toJsonString(persistedCopy(result)));
        } catch (RuntimeException ex) {
            log.warn("[put][缓存回写失败，忽略: {}]", ex.getMessage());
        }
    }

    /** 清单激活后的空间回收（ADR-0008 决策 4）：删除非当前版本的键，失败降级、不回滚激活。 */
    public void reclaimSuperseded(String activeManifestId) {
        try {
            int deleted = repository.deleteAllExcept(activeManifestId);
            log.info("[reclaimSuperseded][active({}) 回收旧版本缓存键 {} 条]", activeManifestId, deleted);
        } catch (RuntimeException ex) {
            log.warn("[reclaimSuperseded][回收失败，降级继续: {}]", ex.getMessage());
        }
    }

    /** 回收长期未命中的键。M2 在启动就绪时执行一次；每日调度归 M8 运维接入。 */
    @EventListener(ApplicationReadyEvent.class)
    public void purgeStaleOnStartup() {
        try {
            // 固定打一条 INFO：这是运维判断「清理任务真的跑了」的唯一信号，0 条也要可见
            int deleted = repository.deleteStale(properties.getCacheRetention());
            log.info("[purgeStale][启动回收过期缓存键 {} 条，保留期 {} 天]",
                    deleted, properties.getCacheRetention().toDays());
        } catch (RuntimeException ex) {
            log.warn("[purgeStale][启动回收失败，降级继续: {}]", ex.getMessage());
        }
    }

    /** 落库前把 cached 置回 false：缓存里存的是「原始结果」，命中由读取侧标记。 */
    private static QueryResult persistedCopy(QueryResult result) {
        QueryResult copy = new QueryResult();
        copy.setManifestId(result.getManifestId());
        copy.setContentHash(result.getContentHash());
        copy.setColumns(result.getColumns());
        copy.setRows(result.getRows());
        copy.setRowCount(result.getRowCount());
        copy.setTruncated(result.isTruncated());
        copy.setElapsedMs(result.getElapsedMs());
        copy.setLookupRows(result.getLookupRows() == null ? null : List.copyOf(result.getLookupRows()));
        copy.setCached(false);
        return copy;
    }

}
