package com.ontos.module.query.core.skill;

import java.util.List;
import java.util.Map;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3/W3：skill.entity_lookup 槽位 → DSL 的严格组装（未知槽位 / 缺必填 / 类型不符一律拒绝）。 */
class EntityLookupSkillTest {

    private final EntityLookupSkill skill = new EntityLookupSkill();

    private static final String MANIFEST_ID = "2026.11.08-eighth";

    @Test
    void shouldBuildSingleLookupDsl() {
        QueryDsl dsl = skill.build(Map.of(
                "entityId", "concept.customer",
                "attributes", List.of("property.customer.email"),
                "identity", Map.of("propertyId", "property.customer.customer_id", "value", "c-1")), MANIFEST_ID);

        assertEquals(MANIFEST_ID, dsl.getManifestId(), "manifestId 由服务层注入");
        assertEquals(QueryDsl.AnalysisMode.LOOKUP, dsl.getMode());
        assertEquals("concept.customer", dsl.getEntityId());
        assertEquals("c-1", dsl.getIdentity().getValue());
    }

    @Test
    void shouldBuildBatchLookupDsl() {
        QueryDsl dsl = skill.build(Map.of(
                "entityId", "concept.customer",
                "attributes", List.of("property.customer.email"),
                "mode", "lookup_batch",
                "identityPropertyId", "property.customer.customer_id",
                "identities", List.of("c-1", "c-2"),
                "limit", 2), MANIFEST_ID);

        assertEquals(QueryDsl.AnalysisMode.LOOKUP_BATCH, dsl.getMode());
        assertEquals(List.of("c-1", "c-2"), dsl.getIdentities());
        assertEquals(2, dsl.getLimit());
    }

    @Test
    void shouldRejectUnknownSlot() {
        ServiceException ex = assertThrows(ServiceException.class, () -> skill.build(Map.of(
                "entityId", "concept.customer",
                "attributes", List.of("property.customer.email"),
                "sql", "1=1"), MANIFEST_ID));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("未知槽位"), "能力层是 DSL 封装，不是绕过校验的旁路");
    }

    @Test
    void shouldRejectMissingRequiredSlots() {
        ServiceException ex = assertThrows(ServiceException.class, () -> skill.build(Map.of(), MANIFEST_ID));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("entityId"));
    }

    @Test
    void shouldRejectIllegalMode() {
        ServiceException ex = assertThrows(ServiceException.class, () -> skill.build(Map.of(
                "entityId", "concept.customer",
                "attributes", List.of("property.customer.email"),
                "identity", Map.of("propertyId", "p", "value", "v"),
                "mode", "aggregate"), MANIFEST_ID));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectBadIdentityShape() {
        ServiceException ex = assertThrows(ServiceException.class, () -> skill.build(Map.of(
                "entityId", "concept.customer",
                "attributes", List.of("property.customer.email"),
                "identity", "c-1"), MANIFEST_ID));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("identity"));
    }

    @Test
    void shouldRejectBadLimit() {
        ServiceException ex = assertThrows(ServiceException.class, () -> skill.build(Map.of(
                "entityId", "concept.customer",
                "attributes", List.of("property.customer.email"),
                "identity", Map.of("propertyId", "p", "value", "v"),
                "limit", "10"), MANIFEST_ID));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("limit"));
    }
}
