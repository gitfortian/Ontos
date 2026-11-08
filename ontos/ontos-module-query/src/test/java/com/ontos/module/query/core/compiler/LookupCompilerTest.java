package com.ontos.module.query.core.compiler;

import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_ATTRIBUTE_NOT_PROJECTABLE;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_CROSS_TABLE_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_IDENTITY_REQUIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W3/T3.2：lookup / lookup_batch 的确定性投影编译（v1.4 §6.3.1）。 */
class LookupCompilerTest {

    private final SqlCompiler compiler = new SqlCompiler();
    private final PublishedManifest manifest = TestFixtures.lookupManifest();

    @Test
    void shouldCompileSingleLookupWithBoundedProbe() {
        CompiledQuery compiled = compiler.compile(TestFixtures.singleLookup(), manifest);
        String sql = compiled.getSql().toLowerCase();

        assertEquals("LOOKUP", compiled.getMode());
        assertEquals(TestFixtures.CUSTOMER_CONCEPT, compiled.getEntityId());
        assertEquals(List.of(TestFixtures.CUSTOMER_EMAIL_PROPERTY), compiled.getAttributes());
        // LIMIT 2 用于探测「标识不唯一」，命中 2 行由 service 拒绝，不静默截断
        assertEquals(2, compiled.getAppliedLimit());
        assertTrue(sql.contains("from \"customer\""), "投影表应来自属性物理列: " + sql);
        assertTrue(sql.contains("__identity"), "批量/单实体统一用 __identity 对齐: " + sql);
        assertTrue(sql.contains("where"), sql);
        assertNull(compiled.getIdentities());
    }

    @Test
    void shouldCompileBatchLookupWithParameterizedIn() {
        CompiledQuery compiled = compiler.compile(TestFixtures.batchLookup(), manifest);
        String sql = compiled.getSql().toLowerCase();

        assertEquals("LOOKUP_BATCH", compiled.getMode());
        assertEquals(List.of("c-1001", "c-1002"), compiled.getIdentities());
        assertTrue(sql.contains(" in "), "批量必须走参数化 IN: " + sql);
        assertEquals(2, compiled.getAppliedLimit());
    }

    @Test
    void shouldRejectNonProjectableAttributeDefensively() {
        QueryDsl dsl = TestFixtures.singleLookup();
        dsl.setAttributes(List.of(TestFixtures.CUSTOMER_TIER_PROPERTY));
        ServiceException ex = assertThrows(ServiceException.class, () -> compiler.compile(dsl, manifest));
        assertEquals(LOOKUP_ATTRIBUTE_NOT_PROJECTABLE.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectCrossTableProjectionDefensively() {
        QueryDsl dsl = TestFixtures.batchLookup();
        dsl.setAttributes(List.of(TestFixtures.CUSTOMER_EMAIL_PROPERTY, TestFixtures.CUSTOMER_BIO_PROPERTY));
        ServiceException ex = assertThrows(ServiceException.class, () -> compiler.compile(dsl, manifest));
        assertEquals(LOOKUP_CROSS_TABLE_UNSUPPORTED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectIdentityFromAnotherEntityOrTable() {
        QueryDsl dsl = TestFixtures.singleLookup();
        QueryDsl.LookupIdentity identity = new QueryDsl.LookupIdentity();
        identity.setPropertyId(TestFixtures.ORDER_NO_PROPERTY);
        identity.setValue("o-1");
        dsl.setIdentity(identity);
        ServiceException ex = assertThrows(ServiceException.class, () -> compiler.compile(dsl, manifest));
        assertEquals(LOOKUP_IDENTITY_REQUIRED.getCode(), ex.getCode());
    }

}
