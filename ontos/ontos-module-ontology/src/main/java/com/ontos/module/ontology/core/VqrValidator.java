package com.ontos.module.ontology.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VQR 资产校验（v1.4 §11，M6/W3/T3.4）：发布预检与资产管理面共用同一套判定
 * （对齐 {@link SkillValidator} 模式——ManifestStructureValidator 委派本类，不另立规则）。
 *
 * <p>校验面对应入库七条件（§11.2）中可在清单/管理面静态判定的部分：
 * <ul>
 *   <li>DSL Schema 合法（条件 1）：dslTemplate 必须是非空 object，占位符 {@code ${param.x}}
 *       必须在 paramsSchema 中声明（未知占位符 = 拼写错误，绑定期无法取值）；</li>
 *   <li>依赖绑定稳定（条件 2）：模板中出现的资产 ID（metric./dim./property./concept./rule./skill.
 *       前缀）必须存在于目标清单且已发布；</li>
 *   <li>Golden / 结果校验 / 目标库执行 / 权限测试 / 结果回答声明问题（条件 3–6）：ADR-0017 D4
 *       ——执行类证据由 catalog 之外流水线产生，此处只校验**证据引用存在**（published 必填
 *       goldenRef + resultCheckRef），不连库执行、不重复校验证据内容；</li>
 *   <li>Owner / 版本 / 新鲜度 / 失效条件齐全（条件 7）：published 必填 SemVer、
 *       freshnessLimitHours ≥ 1、invalidationConditions 非空且每条 {assetId, trigger} 的
 *       assetId 必须落在该 VQR 自己的依赖集内（声明与实际依赖不一致 = 失效分析漏报）。</li>
 * </ul>
 * 点赞只加候选优先级，不触发发布（§11.2 末句）——本类不提供任何「从点赞创建发布态」的路径。
 */
public class VqrValidator {

    private static final Pattern SEMVER = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    /** 模板字符串里的参数占位符：${param.days} → days。 */
    private static final Pattern PARAM_PLACEHOLDER = Pattern.compile("\\$\\{param\\.([A-Za-z0-9_]+)}");
    /**
     * 资产 ID 识别（模板值里整串匹配才计）：前缀封闭词表；rule./skill. 的绑定稳定性
     * 按状态判定，本体/语义资产在清单中即视为已发布（清单是发布产物）。
     */
    private static final Pattern ASSET_ID = Pattern.compile(
            "^(metric|dim|property|concept|rule|skill)\\.[A-Za-z0-9_.-]+$");
    private static final Set<String> TRIGGERS = Set.of("deprecated", "incompatible_change");

    public List<ManifestStructureValidator.Violation> validate(ManifestPayload payload) {
        List<ManifestStructureValidator.Violation> violations = new ArrayList<>();
        if (payload == null || payload.getVqrs() == null) {
            return violations;
        }
        Set<String> ids = new HashSet<>();
        for (ManifestPayload.VqrMeta vqr : payload.getVqrs()) {
            String at = "vqr." + vqr.getVqrId();
            if (vqr.getVqrId() == null || vqr.getVqrId().isBlank()) {
                violations.add(new ManifestStructureValidator.Violation("vqr", "VQR ID 不能为空"));
                continue;
            }
            if (!ids.add(vqr.getVqrId())) {
                violations.add(new ManifestStructureValidator.Violation(at, "VQR ID 重复"));
            }
        }
        AssetIndex index = AssetIndex.of(payload);
        for (ManifestPayload.VqrMeta vqr : payload.getVqrs()) {
            violations.addAll(validateAgainst(vqr, index, "vqr." + vqr.getVqrId()));
        }
        return violations;
    }

    /** 单 VQR 校验（资产管理面发布时对候选 VQR 与目标清单复用同一套规则）。 */
    public List<ManifestStructureValidator.Violation> validateOne(ManifestPayload.VqrMeta vqr,
                                                                  ManifestPayload payload) {
        if (vqr == null) {
            return List.of(new ManifestStructureValidator.Violation("vqr", "VQR 不能为空"));
        }
        if (vqr.getVqrId() == null || vqr.getVqrId().isBlank()) {
            return List.of(new ManifestStructureValidator.Violation("vqr", "VQR ID 不能为空"));
        }
        return validateAgainst(vqr, AssetIndex.of(payload), "vqr." + vqr.getVqrId());
    }

    /**
     * 结构校验（不含依赖绑定）：草稿创建期资产引用可能尚未进入清单（随后续发布补齐），
     * 只验生命周期/模板形状/占位符声明/证据引用形状。绑定稳定性（条件 2）在发布闸门
     * 以目标清单判定（{@link #validateOne}）。
     */
    public List<ManifestStructureValidator.Violation> validateStructure(ManifestPayload.VqrMeta vqr) {
        return validateAgainst(vqr, null, "vqr."
                + (vqr == null || vqr.getVqrId() == null ? "?" : vqr.getVqrId()));
    }

    private List<ManifestStructureValidator.Violation> validateAgainst(ManifestPayload.VqrMeta vqr,
                                                                       AssetIndex index, String at) {
        List<ManifestStructureValidator.Violation> violations = new ArrayList<>();
        if (vqr.getName() == null || vqr.getName().isBlank()) {
            violations.add(new ManifestStructureValidator.Violation(at, "name 必填"));
        }
        if (vqr.getOwner() == null || vqr.getOwner().isBlank()) {
            violations.add(new ManifestStructureValidator.Violation(at, "owner 必填"));
        }
        if (vqr.getVersion() == null || !SEMVER.matcher(vqr.getVersion()).matches()) {
            violations.add(new ManifestStructureValidator.Violation(at, "version 必须 SemVer: "
                    + vqr.getVersion()));
        }
        String status = vqr.getStatus();
        if (!"draft".equals(status) && !"reviewing".equals(status)
                && !"published".equals(status) && !"retired".equals(status)) {
            violations.add(new ManifestStructureValidator.Violation(at, "status 非法: " + status));
        }
        if ("published".equals(status)
                && (vqr.getReviewer() == null || vqr.getReviewer().isBlank())) {
            violations.add(new ManifestStructureValidator.Violation(at, "published 必须有审核人记录 (reviewer)"));
        }
        if (vqr.getQuestionPattern() == null || vqr.getQuestionPattern().isBlank()) {
            violations.add(new ManifestStructureValidator.Violation(at, "questionPattern 必填"));
        }
        boolean published = "published".equals(status);

        // 条件 1：DSL Schema 合法——模板非空 object + 占位符全部声明
        Map<String, Object> template = vqr.getDslTemplate();
        if (template == null || template.isEmpty()) {
            violations.add(new ManifestStructureValidator.Violation(at, "dslTemplate 必须是非空 object"));
        } else {
            Set<String> declaredParams = vqr.getParamsSchema() == null
                    ? Set.of() : vqr.getParamsSchema().keySet();
            for (String placeholder : placeholdersOf(template)) {
                if (!declaredParams.contains(placeholder)) {
                    violations.add(new ManifestStructureValidator.Violation(at,
                            "dslTemplate 占位符未在 paramsSchema 声明: ${param." + placeholder + "}"));
                }
            }
            if (vqr.getParamsSchema() != null && vqr.getParamsSchema().isEmpty()
                    && !placeholdersOf(template).isEmpty()) {
                violations.add(new ManifestStructureValidator.Violation(at, "paramsSchema 必须是 object"));
            }
        }

        // 条件 2：依赖绑定稳定——模板资产 ID 必须在目标清单且已发布
        //（index == null = 结构校验态，绑定留待发布闸门以目标清单判定）
        Set<String> dependencies = dependenciesOf(template);
        if (index != null) {
            for (String assetId : dependencies) {
                if (!index.publishedContains(assetId)) {
                    violations.add(new ManifestStructureValidator.Violation(at,
                            "dslTemplate 引用资产不存在或未发布: " + assetId));
                }
            }
        }

        // 条件 3–6（D4 证据外置）：published 必须携带执行类证据引用（catalog 不连库复验）
        if (published) {
            if (vqr.getGoldenRef() == null || vqr.getGoldenRef().isBlank()) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "published 必须携带 Golden 证据引用 (goldenRef)——入库条件 3"));
            }
            if (vqr.getResultCheckRef() == null || vqr.getResultCheckRef().isBlank()) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "published 必须携带结果校验证据引用 (resultCheckRef，含目标库执行/权限测试/结果对题)"
                                + "——入库条件 4-6，证据由外部流水线产生"));
            }
            // 条件 7：新鲜度与失效条件齐全
            if (vqr.getFreshnessLimitHours() == null || vqr.getFreshnessLimitHours() < 1) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "published 必须声明数据新鲜度上限 (freshnessLimitHours ≥ 1)——入库条件 7"));
            }
            if (vqr.getInvalidationConditions() == null || vqr.getInvalidationConditions().isEmpty()) {
                violations.add(new ManifestStructureValidator.Violation(at,
                        "published 必须声明失效条件 (invalidationConditions 非空)——入库条件 7"));
            } else {
                for (Map<String, Object> condition : vqr.getInvalidationConditions()) {
                    Object assetId = condition.get("assetId");
                    Object trigger = condition.get("trigger");
                    if (!(assetId instanceof String id) || id.isBlank()) {
                        violations.add(new ManifestStructureValidator.Violation(at,
                                "失效条件缺少 assetId: " + condition));
                        continue;
                    }
                    if (!(trigger instanceof String t) || !TRIGGERS.contains(t)) {
                        violations.add(new ManifestStructureValidator.Violation(at,
                                "失效条件 trigger 非法（deprecated | incompatible_change）: " + trigger));
                        continue;
                    }
                    // 声明与实际依赖一致：失效条件指向依赖集外资产 = 永不触发的死声明
                    if (!dependencies.contains(id)) {
                        violations.add(new ManifestStructureValidator.Violation(at,
                                "失效条件 assetId 不在 VQR 依赖集内: " + id));
                    }
                }
            }
        }
        return violations;
    }

    /** 递归收集模板中的 ${param.x} 占位符名。 */
    static Set<String> placeholdersOf(Map<String, Object> template) {
        Set<String> names = new LinkedHashSet<>();
        collect(template, value -> {
            Matcher matcher = PARAM_PLACEHOLDER.matcher(value);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        });
        return Set.copyOf(names);
    }

    /** 递归收集模板中的资产 ID（整串匹配前缀词表）。 */
    static Set<String> dependenciesOf(Map<String, Object> template) {
        Set<String> ids = new LinkedHashSet<>();
        collect(template, value -> {
            if (ASSET_ID.matcher(value.trim()).matches()) {
                ids.add(value.trim());
            }
        });
        return Set.copyOf(ids);
    }

    private static void collect(Object node, java.util.function.Consumer<String> onString) {
        if (node instanceof Map<?, ?> map) {
            map.values().forEach(value -> collect(value, onString));
        } else if (node instanceof List<?> list) {
            list.forEach(value -> collect(value, onString));
        } else if (node instanceof String value) {
            onString.accept(value);
        }
    }

    /** 目标清单的已发布资产索引（§11.2 条件 2 的判定源）。 */
    record AssetIndex(Set<String> assetIds) {
        static AssetIndex of(ManifestPayload payload) {
            Set<String> ids = new HashSet<>();
            if (payload == null) {
                return new AssetIndex(Set.copyOf(ids));
            }
            if (payload.getOntology() != null) {
                payload.getOntology().getConcepts().forEach(c -> addIfPresent(ids, c.getId()));
                payload.getOntology().getProperties().forEach(p -> addIfPresent(ids, p.getId()));
            }
            if (payload.getSemantic() != null) {
                payload.getSemantic().getMetrics().forEach(m -> addIfPresent(ids, m.getId()));
                payload.getSemantic().getDimensions().forEach(d -> addIfPresent(ids, d.getId()));
            }
            if (payload.getRules() != null) {
                payload.getRules().stream()
                        .filter(rule -> "published".equals(rule.getStatus()))
                        .map(ManifestPayload.Rule::getId)
                        .forEach(ids::add);
            }
            if (payload.getSkills() != null) {
                payload.getSkills().stream()
                        .filter(skill -> "published".equals(skill.getStatus()))
                        .map(ManifestPayload.SkillMeta::getSkillId)
                        .forEach(ids::add);
            }
            return new AssetIndex(Set.copyOf(ids));
        }

        private static void addIfPresent(Set<String> ids, String id) {
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }

        boolean publishedContains(String assetId) {
            return assetIds.contains(assetId);
        }
    }
}
