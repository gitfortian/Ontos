package com.ontos.module.query.core.cache;

import java.time.LocalDate;
import java.util.Set;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2/W4/T4.4：缓存键口径（ADR-0008 决策 1/2）。 */
class QueryCacheKeyTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 11, 8);

    private static RequestContext context(String tenant, String user, Set<String> roles) {
        return new RequestContext(tenant, user, "session-1", 1, null, "trace-1", roles, true);
    }

    private static String key(RequestContext context, QueryDsl dsl, int limit) {
        return QueryCacheKey.of(context, TestFixtures.manifest(), dsl, limit, ANCHOR);
    }

    @Test
    void shouldBeDeterministicForSameInputs() {
        QueryDsl dsl = TestFixtures.query();
        String first = key(context("t1", "u1", Set.of("analyst")), dsl, 500);
        String second = key(context("t1", "u1", Set.of("analyst")), dsl, 500);

        assertEquals(first, second);
        assertEquals(64, first.length(), "SHA-256 十六进制必须是 64 位");
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    void shouldDifferByTenantUserAndRole() {
        QueryDsl dsl = TestFixtures.query();
        String base = key(context("t1", "u1", Set.of("analyst")), dsl, 500);

        assertNotEquals(base, key(context("t2", "u1", Set.of("analyst")), dsl, 500), "租户必须隔离");
        assertNotEquals(base, key(context("t1", "u2", Set.of("analyst")), dsl, 500), "用户必须隔离");
        assertNotEquals(base, key(context("t1", "u1", Set.of("admin")), dsl, 500), "角色变化必须换键");
    }

    @Test
    void shouldDifferByTimeAnchor() {
        QueryDsl dsl = TestFixtures.query();
        String today = QueryCacheKey.of(context("t1", "u1", Set.of()), TestFixtures.manifest(), dsl, 500,
                ANCHOR);
        String tomorrow = QueryCacheKey.of(context("t1", "u1", Set.of()), TestFixtures.manifest(), dsl, 500,
                ANCHOR.plusDays(1));

        assertNotEquals(today, tomorrow, "跨日时间基准必须换键，防止相对时间复用旧结果");
    }

    @Test
    void shouldDifferByBatchSizeAndQueryShape() {
        QueryDsl dsl = TestFixtures.query();
        String base = key(context("t1", "u1", Set.of()), dsl, 500);
        assertNotEquals(base, key(context("t1", "u1", Set.of()), dsl, 100), "生效行数上限入键");

        QueryDsl other = TestFixtures.query();
        other.setMetricId(TestFixtures.DIMENSION_ID);
        assertNotEquals(base, key(context("t1", "u1", Set.of()), other, 500), "查询本体入键");
    }

}
