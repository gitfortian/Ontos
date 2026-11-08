package com.ontos.module.query.service.cache;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.query.core.QueryProperties;
import com.ontos.module.query.core.executor.QueryResult;
import com.ontos.module.query.dal.dataobject.QueryCacheDO;
import com.ontos.module.query.dal.mysql.QueryCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M2/W4/T4.4：缓存读写必须「失败降级」，不能把可用性优化变成故障源（ADR-0008 决策 3）。 */
class QueryCacheServiceTest {

    private final QueryCacheRepository repository = mock(QueryCacheRepository.class);
    private QueryCacheService service;

    @BeforeEach
    void setUp() {
        service = new QueryCacheService(repository, new QueryProperties());
    }

    private static String resultJson() {
        QueryResult result = new QueryResult();
        result.setManifestId("manifest-001");
        result.setColumns(List.of("value"));
        result.setRows(List.of(Map.of("value", 7)));
        result.setRowCount(1);
        return JsonUtils.toJsonString(result);
    }

    @Test
    void shouldReturnEmptyOnMiss() {
        when(repository.selectByKey("k1")).thenReturn(Optional.empty());

        assertTrue(service.get("k1").isEmpty());
    }

    @Test
    void shouldMarkHitAndTouchCounter() {
        QueryCacheDO row = new QueryCacheDO();
        row.setCacheKey("k1");
        row.setResultJson(resultJson());
        when(repository.selectByKey("k1")).thenReturn(Optional.of(row));

        QueryResult result = service.get("k1").orElseThrow();

        assertTrue(result.isCached());
        assertEquals(1, result.getRowCount());
        verify(repository).touch("k1");
    }

    @Test
    void shouldDegradeWhenReadFails() {
        when(repository.selectByKey("k1")).thenThrow(new IllegalStateException("db down"));

        // 读失败按未命中处理：调用方会走完整链路，不在缓存上 fail-closed
        assertTrue(service.get("k1").isEmpty());
    }

    @Test
    void shouldDegradeWhenWriteFails() {
        doThrow(new IllegalStateException("db down")).when(repository)
                .upsert(anyString(), anyString(), anyString(), anyString(), anyString());

        QueryResult result = new QueryResult();
        result.setRowCount(1);

        assertDoesNotThrow(() -> service.put("k1", "manifest-001", "hash-1", "QUERY", result));
    }

    @Test
    void shouldPersistWithCachedFlagReset() {
        QueryResult result = new QueryResult();
        result.setRowCount(2);
        result.setCached(true);

        service.put("k1", "manifest-001", "hash-1", "QUERY", result);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(repository).upsert(anyString(), anyString(), anyString(), anyString(), json.capture());
        assertTrue(json.getValue().contains("\"cached\":false"),
                "缓存体里的 cached 必须是 false，命中由读取侧标记: " + json.getValue());
        assertTrue(result.isCached(), "回写不应改写调用方手里的结果对象");
    }

    @Test
    void shouldDegradeWhenReclaimFails() {
        when(repository.deleteAllExcept("manifest-001")).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service.reclaimSuperseded("manifest-001"));
    }

}
