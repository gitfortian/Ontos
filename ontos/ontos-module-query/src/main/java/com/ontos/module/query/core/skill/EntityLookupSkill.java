package com.ontos.module.query.core.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.springframework.stereotype.Component;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;

/**
 * {@code skill.entity_lookup}（M3/W3，v1.4 §6.3.1）：实体直查能力的最小闭环。
 *
 * <p>槽位（全部业务 ID，不出现物理名）：
 * <ul>
 *   <li>entityId（必填）：投影实体（Concept ID）；</li>
 *   <li>attributes（必填）：投影属性（Property ID 列表，非空）；</li>
 *   <li>mode：{@code lookup}（默认，单实体）| {@code lookup_batch}；</li>
 *   <li>identity：单实体标识 {@code {propertyId, value}}（mode=lookup 必填）；</li>
 *   <li>identityPropertyId + identities：批量标识（mode=lookup_batch 必填）；</li>
 *   <li>booleanMetricId：可选布尔判定列（引用 ruleId 型指标）；</li>
 *   <li>limit：可选行数上限（语义交给下游校验器定档）。</li>
 * </ul>
 *
 * <p>能力层不猜默认值：单实体/批量的结构差异必须由调用方显式表达，
 * 否则下游「缺失行统一不可用」的存在性保护无从对齐。
 */
@Component
public class EntityLookupSkill implements SkillHandler {

    public static final String SKILL_ID = "entity_lookup";

    private static final Set<String> SLOTS = Set.of(
            "entityId", "attributes", "mode", "identity", "identities",
            "identityPropertyId", "booleanMetricId", "limit");

    @Override
    public java.util.List<String> builtinRefs() {
        // 单实体（LOOKUP.entity）与批量（LOOKUP_BATCH.batch）共用同一构造器——批量是 mode 槽位
        return java.util.List.of("builtin.LOOKUP.entity", "builtin.LOOKUP_BATCH.batch");
    }

    @Override
    public SkillDescriptor descriptor() {
        return new SkillDescriptor(SKILL_ID, "实体查询", "按唯一标识直查实体属性，支持单实体与批量",
                List.of(
                        new SkillDescriptor.SkillSlot("entityId", true, "投影实体（Concept ID）"),
                        new SkillDescriptor.SkillSlot("attributes", true, "投影属性（Property ID 列表）"),
                        new SkillDescriptor.SkillSlot("mode", false, "lookup（默认）| lookup_batch"),
                        new SkillDescriptor.SkillSlot("identity", false, "单实体标识 {propertyId, value}"),
                        new SkillDescriptor.SkillSlot("identityPropertyId", false, "批量标识属性（Property ID）"),
                        new SkillDescriptor.SkillSlot("identities", false, "批量标识值列表"),
                        new SkillDescriptor.SkillSlot("booleanMetricId", false, "布尔判定列（ruleId 型指标）"),
                        new SkillDescriptor.SkillSlot("limit", false, "行数上限")));
    }

    @Override
    public QueryDsl build(Map<String, Object> slots, String manifestId) {
        Map<String, Object> input = slots == null ? Map.of() : slots;
        for (String key : input.keySet()) {
            if (!SLOTS.contains(key)) {
                throw exception(DSL_INVALID, "skill.entity_lookup 未知槽位: " + key);
            }
        }
        String entityId = requireText(input, "entityId");
        List<String> attributes = requireTextList(input, "attributes");

        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId(manifestId);
        String mode = optionalText(input, "mode");
        boolean batch;
        if (mode == null || "lookup".equals(mode)) {
            batch = false;
            dsl.setMode(QueryDsl.AnalysisMode.LOOKUP);
        } else if ("lookup_batch".equals(mode)) {
            batch = true;
            dsl.setMode(QueryDsl.AnalysisMode.LOOKUP_BATCH);
        } else {
            throw exception(DSL_INVALID, "skill.entity_lookup mode 仅允许 lookup / lookup_batch: " + mode);
        }
        dsl.setEntityId(entityId);
        dsl.setAttributes(new ArrayList<>(attributes));

        if (input.containsKey("booleanMetricId")) {
            dsl.setBooleanMetricId(requireText(input, "booleanMetricId"));
        }
        if (input.containsKey("limit")) {
            Object limit = input.get("limit");
            if (!(limit instanceof Number number) || number.intValue() < 1) {
                throw exception(DSL_INVALID, "skill.entity_lookup limit 必须是正整数");
            }
            dsl.setLimit(number.intValue());
        }

        if (batch) {
            dsl.setIdentityPropertyId(requireText(input, "identityPropertyId"));
            dsl.setIdentities(new ArrayList<>(requireTextList(input, "identities")));
        } else {
            Object identity = input.get("identity");
            if (!(identity instanceof Map<?, ?> map)
                    || !(map.get("propertyId") instanceof String propertyId) || propertyId.isBlank()
                    || !(map.get("value") instanceof String value) || value.isBlank()) {
                throw exception(DSL_INVALID,
                        "skill.entity_lookup 单实体必须提供 identity: {propertyId, value}");
            }
            QueryDsl.LookupIdentity lookupIdentity = new QueryDsl.LookupIdentity();
            lookupIdentity.setPropertyId(propertyId);
            lookupIdentity.setValue(value);
            dsl.setIdentity(lookupIdentity);
        }
        return dsl;
    }

    private static String requireText(Map<String, Object> input, String name) {
        String value = optionalText(input, name);
        if (value == null) {
            throw exception(DSL_INVALID, "skill.entity_lookup 缺少必填槽位: " + name);
        }
        return value;
    }

    private static String optionalText(Map<String, Object> input, String name) {
        Object value = input.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw exception(DSL_INVALID, "skill.entity_lookup 槽位必须是字符串: " + name);
        }
        return text.trim();
    }

    private static List<String> requireTextList(Map<String, Object> input, String name) {
        Object value = input.get(name);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw exception(DSL_INVALID, "skill.entity_lookup 缺少必填槽位（非空字符串列表）: " + name);
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw exception(DSL_INVALID, "skill.entity_lookup 槽位必须是字符串列表: " + name);
            }
            result.add(text.trim());
        }
        return result;
    }
}
