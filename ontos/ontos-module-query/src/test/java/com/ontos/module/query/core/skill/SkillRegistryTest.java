package com.ontos.module.query.core.skill;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3/W3：能力注册中心（Spring 注入聚合，无按 ID 硬编码分支）。 */
class SkillRegistryTest {

    @Test
    void shouldListAndResolveHandlers() {
        SkillRegistry registry = new SkillRegistry(List.of(new EntityLookupSkill()));

        assertEquals(1, registry.list().size());
        assertEquals("entity_lookup", registry.list().get(0).skillId());
        assertTrue(registry.byId("entity_lookup").isPresent());
        assertTrue(registry.byId("nope").isEmpty());
    }
}
