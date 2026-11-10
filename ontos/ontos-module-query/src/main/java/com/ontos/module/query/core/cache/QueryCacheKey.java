package com.ontos.module.query.core.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.query.core.time.TimeExpressionParser;

/**
 * 查询结果缓存键（M2/W4/T4.4，ADR-0008 决策 1/2）。
 *
 * <p>键的输入是技术选型 §4.9 字段清单的**全量序列化**，缺一项即不构成键；用 TreeMap 保证字段顺序稳定，
 * 再整体 SHA-256。字段只增不改语义——口径变更必须走 ADR-0008 新版本，并升 {@link #SCHEMA_VERSION}
 * 让旧键自然失配。
 *
 * <p>M2 暂无会话上下文与自然语言问题（M5 才有），对应字段取 {@code none} 占位而不是省略：
 * 省略会让「有没有这一项」变成隐含版本差异。
 */
public final class QueryCacheKey {

    /**
     * 键结构版本：任何字段语义变化都必须升这里，旧键随之失效（禁止原地改口径）。
     * 1.2（M6/W3/T3.5，v0.2 F-5）：补 scenario_version 与 vqrId/vqrVersion——
     * VQR 版本变化必须失效（§3.6 缓存隔离）；skill 版本随清单 contentHash 失效（在清单内）。
     */
    public static final String SCHEMA_VERSION = "cache-key-1.2";

    private static final String NONE = "none";

    private QueryCacheKey() {
    }

    /**
     * @param appliedLimit 生效行数上限（校验器写回后的值），属于 §4.9 的 batch_size
     * @param anchorDate   时间基准日（Asia/Shanghai）：跨日即换键，防止「近 7 天」跨日复用旧结果
     * @param runtimePolicyVersion 运行时生效策略快照版本（M4/W1 审核 F-2：prepare 先行已保证不越权，
     *                             入键是防御纵深——快照激活/降权即换键，旧条目天然失配）
     */
    public static String of(RequestContext context, PublishedManifest manifest, QueryDsl queryDsl,
                            int appliedLimit, LocalDate anchorDate, String runtimePolicyVersion) {
        Map<String, Object> input = new TreeMap<>();
        input.put("schema", SCHEMA_VERSION);
        // 版本全量入键：任一资产版本变化都不得复用旧结果
        input.put("manifestId", text(manifest.getManifestId()));
        input.put("contentHash", text(manifest.getContentHash()));
        input.put("ontologyVersion", text(manifest.getOntologyVersion()));
        input.put("semanticVersion", text(manifest.getSemanticVersion()));
        input.put("policyVersion", text(manifest.getPolicyVersion()));
        input.put("runtimePolicyVersion", text(runtimePolicyVersion));
        input.put("ruleVersion", text(manifest.getRuleVersion()));
        input.put("timeParserVersion", text(manifest.getTimeParserVersion()));
        input.put("copyTemplateVersion", text(manifest.getCopyTemplateVersion()));
        // 作用域：租户与用户范围哈希都入键，禁止跨租户/跨角色共享
        input.put("tenantId", text(context.tenantId()));
        input.put("userScopeHash", userScopeHash(context));
        input.put("scenarioId", text(queryDsl.getScenarioId()));
        // 场景版本入键（1.2）：场景升级即换键，旧结果不复用
        input.put("scenarioVersion", text(scenarioVersionOf(manifest, queryDsl.getScenarioId())));
        // VQR 命中通道（1.2）：模板版本变化必须失效（§3.6），即使绑定产物相同
        input.put("vqrId", text(queryDsl.getVqrId()));
        input.put("vqrVersion", text(queryDsl.getVqrVersion()));
        // 查询本体：规范化 DSL（含服务端回填的 timeRange/joins/limit），等价于 §4.9 的 query_parameters
        input.put("queryParameters", JsonUtils.toJsonString(queryDsl));
        input.put("queryShape", queryDsl.getMode() == null ? NONE : queryDsl.getMode().name());
        input.put("batchSize", appliedLimit);
        input.put("displayTransformTimeBucket", NONE);
        input.put("freshnessBucket", NONE);
        input.put("sessionQueryContextHash", NONE);
        input.put("resolvedEntityHashes", NONE);
        input.put("normalizedQuestion", NONE);
        input.put("timezone", TimeExpressionParser.ZONE);
        input.put("timeAnchorDate", anchorDate.toString());
        return sha256(JsonUtils.toJsonString(input));
    }

    /** 场景版本解析（1.2）：生效清单场景段按 ID 查 version；无场景/未找到返回 none。 */
    private static String scenarioVersionOf(PublishedManifest manifest, String scenarioId) {
        if (scenarioId == null || manifest.getScenarios() == null) {
            return NONE;
        }
        return manifest.getScenarios().stream()
                .filter(scenario -> scenarioId.equals(scenario.getId()))
                .map(ManifestPayload.ScenarioMeta::getVersion)
                .findFirst().orElse(NONE);
    }

    /** 用户范围哈希：userId + 角色集合（排序后），角色变更即换键，避免降权后复用旧结果。 */
    static String userScopeHash(RequestContext context) {
        String roles = context.roles() == null ? "" : context.roles().stream().sorted()
                .reduce((a, b) -> a + "," + b).orElse("");
        return sha256(text(context.userId()) + "|" + roles);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? NONE : value;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // JDK 必带 SHA-256；走到这里说明运行环境被裁剪，宁可失败也不降级到弱哈希
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

}
