package com.ontos.module.system.service.sensitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.system.dal.dataobject.SensitiveQueryEventDO;
import com.ontos.module.system.dal.mysql.ApprovalRequestMapper;
import com.ontos.module.system.dal.mysql.SensitiveQueryEventMapper;
import com.ontos.module.system.service.approval.ApprovalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.system.enums.ErrorCodeConstants.SENSITIVE_EVENT_INVALID;

/**
 * 跨轮敏感累计：记账 + 两层窗口聚合判定 + 审批联动（M4/W2，技术选型 §4.8 / ADR-0002 / 规格 §3.2）。
 *
 * <p><b>记账口径</b>：每次敏感访问按适用层各记一行（同一资产可叠加 pii + existence 两层，
 * 叠加语义无法用单行表达）；禁止进程内计数——写入、窗口聚合、判定、审批创建在同一个
 * System DB 事务内完成（{@link TransactionTemplate}），跨副本天然一致。
 *
 * <p><b>判定口径</b>（M4 规格 §3.2 计数制；pattern_tag 照记供审计回放）：
 * session 级（会话生命周期，不加时间上界）与 user+tenant 级（按层窗口滑动）各自计数、
 * 各自与同一阈值 T 比较，互不累加，任一层超阈值即触发（OR，动作取档位较高者）。
 *
 * <p><b>ADR-0002 即时动作</b>（不经 risk≥T 比较，仍记账）：
 * <ul>
 *   <li>精确差分：L1 层、与同层上一条已记账事件 filters 三元组集合差恰为 1、
 *       两个基数均 &gt; 1 且基数差恰为 1。结果集包含关系由 filters 的 AND 单调性
 *       严格蕴含（封闭算子集对三元组子集关系单调），无需行级比对；</li>
 *   <li>个体探针：L1 层且 result_cardinality ≤ 1 直接转审批（决策 2 约束 3）。</li>
 * </ul>
 *
 * <p><b>触发动作</b>（§4.8.3 分级）：转审批（L1 ≥T / 即时动作）或拒答
 * （L1 ≥2T、L2 ≥T、L3 ≥T；「临时冻结」由窗口持续超阈值天然实现，无进程内状态）。
 * 对外统一 {@code SENSITIVE_QUERY_SEQUENCE} 文案，细分分支只进审计。
 */
@Slf4j
@Service
public class SensitiveEventService {

    public static final String CLASS_PII = "pii";
    public static final String CLASS_EXISTENCE = "existence";
    public static final String CLASS_LABEL = "label";

    public static final String TAG_PLAIN = "plain";
    public static final String TAG_DIFFERENTIAL_INSTANT = "differential_instant";
    public static final String TAG_INDIVIDUAL_PROBE = "individual_probe";

    /** 审计细分 reason_code（对外统一码见 {@code SENSITIVE_QUERY_SEQUENCE}，差异不外露）。 */
    public static final String AUDIT_APPROVAL = "SENSITIVE_SEQUENCE_APPROVAL";
    public static final String AUDIT_BLOCKED = "SENSITIVE_SEQUENCE_BLOCKED";
    public static final String AUDIT_DIFFERENTIAL = "SENSITIVE_DIFFERENTIAL_INSTANT";
    public static final String AUDIT_INDIVIDUAL = "SENSITIVE_INDIVIDUAL_PROBE";
    public static final String AUDIT_WINDOW_DEGRADED = "WINDOW_DEGRADED";

    /** 待记账事件草稿（一行 = 一个适用敏感层）。 */
    public record DraftEvent(String assetId, String sensitivityClass, String filtersHash,
                             String filtersCanonical, int resultCardinality) {
    }

    /** 判定结论：触发与否、档位（拒答 &gt; 转审批）、审批单 ID、放行指纹与审计细分码。 */
    public record SensitiveDecision(boolean triggered, boolean blocked, String approvalId,
                                    String auditReasonCode, String triggeredClass,
                                    String sessionLayer, int sessionCount, int userCount,
                                    int threshold, String grantFingerprint,
                                    String countersDelta) {

        public static SensitiveDecision notTriggered() {
            return new SensitiveDecision(false, false, null, null, null, null, 0, 0, 0, null, null);
        }
    }

    private final SensitiveQueryEventMapper eventMapper;
    private final ApprovalService approvalService;
    private final SensitiveProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final com.ontos.module.system.service.approval.HitlNotifier hitlNotifier;

    @Autowired
    public SensitiveEventService(SensitiveQueryEventMapper eventMapper,
                                 ApprovalService approvalService,
                                 SensitiveProperties properties,
                                 TransactionTemplate transactionTemplate,
                                 com.ontos.module.system.service.approval.HitlNotifier hitlNotifier) {
        this.eventMapper = eventMapper;
        this.approvalService = approvalService;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.hitlNotifier = hitlNotifier;
    }

    /**
     * 记账并判定（单事务）：差分/个体探针检测 → 插入事件 → 两层窗口聚合 → 超阈值建审批单。
     * 草稿为空（全 L0）直接返回不触发，不产生任何写入。
     */
    public SensitiveDecision recordAndEvaluate(RequestContext context, String requestId,
                                               List<DraftEvent> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return SensitiveDecision.notTriggered();
        }
        validateDrafts(drafts);
        return transactionTemplate.execute(status ->
                recordAndEvaluateInTx(context, requestId, drafts));
    }

    private SensitiveDecision recordAndEvaluateInTx(RequestContext context, String requestId,
                                                    List<DraftEvent> drafts) {
        // 1. 逐层记账：先做 ADR-0002 即时动作检测（读同层上一条已记账事件），再插入本行
        List<InstantTrigger> instantTriggers = new ArrayList<>();
        for (DraftEvent draft : drafts) {
            String tag = TAG_PLAIN;
            if (CLASS_PII.equals(draft.sensitivityClass())) {
                if (draft.resultCardinality() <= 1) {
                    // 决策 2 约束 3：L1 个体值不经累计路径，直接转审批
                    tag = TAG_INDIVIDUAL_PROBE;
                    instantTriggers.add(new InstantTrigger(draft, AUDIT_INDIVIDUAL));
                } else {
                    // 上一条取 user 维度最近（不带 session 条件）——换 session 不能绕过差分即时动作；
                    // 跨 session 的持续累计由 user+tenant 级窗口覆盖
                    SensitiveQueryEventDO previous =
                            eventMapper.selectLatest(context.tenantId(), context.userId(),
                                    sensitiveKey(draft));
                    if (isPreciseDifferential(previous, draft)) {
                        tag = TAG_DIFFERENTIAL_INSTANT;
                        instantTriggers.add(new InstantTrigger(draft, AUDIT_DIFFERENTIAL));
                    }
                }
            }
            eventMapper.insert(toRow(context, requestId, draft, tag));
        }

        // 2. 两层窗口聚合判定（每层独立计数、独立比较，互不累加；刚插入的行同事务可见）
        Map<String, LayerCounts> countsByClass = aggregateByClass(context, drafts);
        // 计数器哈希化增量（§4.8 / W3-T3.2）：本次访问后各层计数的哈希，事件行与审计行同值同源
        String countersDelta = countersDeltaHash(countsByClass);
        eventMapper.updateContextDelta(requestId, countersDelta);
        ThresholdBreach breach = firstBreach(countsByClass);

        // 3. 触发动作：即时动作 > 拒答（≥2T 或 L2/L3 ≥T）> 转审批（L1 ≥T）；同事务建审批单
        InstantTrigger instant = instantTriggers.isEmpty() ? null : instantTriggers.get(0);
        if (instant != null) {
            return createApproval(context, requestId, instant.draft(), instant.auditCode(),
                    countsByClass.get(instant.draft().sensitivityClass()), countersDelta);
        }
        if (breach != null) {
            boolean blocked = breach.blocked();
            String auditCode = properties.isUserWindowEnabled()
                    ? (blocked ? AUDIT_BLOCKED : AUDIT_APPROVAL) : AUDIT_WINDOW_DEGRADED;
            return createApproval(context, requestId, breach.draft(), auditCode,
                    countsByClass.get(breach.draft().sensitivityClass()), countersDelta);
        }
        return new SensitiveDecision(false, false, null, null, null, null, 0, 0, 0, null,
                countersDelta);
    }

    /**
     * ADR-0002 决策 1 精确差分：与同层上一条已记账事件相比——L1、filters 三元组集合差恰为 1
     * （一方严格包含另一方）、两基数均 &gt; 1 且基数差恰为 1。
     * 结果集包含由 AND 单调性严格蕴含（见类注释），不做行级比对。
     */
    private boolean isPreciseDifferential(SensitiveQueryEventDO previous, DraftEvent current) {
        if (previous == null) {
            return false;
        }
        List<String> previousSet = parseFilterSet(previous.getFiltersCanonical());
        List<String> currentSet = parseFilterSet(current.filtersCanonical());
        long difference = symmetricDifference(previousSet, currentSet);
        int previousCardinality = previous.getResultCardinality() == null
                ? 0 : previous.getResultCardinality();
        return difference == 1
                && previousCardinality > 1 && current.resultCardinality() > 1
                && Math.abs(previousCardinality - current.resultCardinality()) == 1;
    }

    /** filters 规范化集合："field|operator|value" 三元组列表（由查询侧规范化序列化拆分）。 */
    private static List<String> parseFilterSet(String canonical) {
        if (canonical == null || canonical.isBlank()) {
            return List.of();
        }
        // 约定：查询侧以换行分隔三元组（三元组内部不含换行——序列化时已转义）
        return List.of(canonical.split("\n", -1));
    }

    private static long symmetricDifference(List<String> a, List<String> b) {
        Map<String, Long> counts = new LinkedHashMap<>();
        a.forEach(item -> counts.merge(item, 1L, Long::sum));
        b.forEach(item -> counts.merge(item, -1L, Long::sum));
        // 集合差按去重语义：任一侧出现即计入差集（filters 三元组本身不去重，重复条件无意义，
        // 查询侧规范化时已去重，此处对计数取绝对值再求和等价于对称差大小）
        return counts.values().stream()
                .mapToLong(delta -> delta == 0 ? 0 : 1)
                .sum();
    }

    /** 每个敏感层独立做两层计数（key = sensitivity_class；同层多资产合并计数，§4.8.1 取数键为 sensitive_key， */
    /** 同层同 key 才累计——叠加层各自独立，因此这里按 (class, key) 聚合后取每 class 的最大值）。 */
    private Map<String, LayerCounts> aggregateByClass(RequestContext context, List<DraftEvent> drafts) {
        Map<String, LayerCounts> result = new LinkedHashMap<>();
        Map<String, List<DraftEvent>> byClass = new LinkedHashMap<>();
        for (DraftEvent draft : drafts) {
            byClass.computeIfAbsent(draft.sensitivityClass(), key -> new ArrayList<>()).add(draft);
        }
        for (Map.Entry<String, List<DraftEvent>> entry : byClass.entrySet()) {
            String sensitivityClass = entry.getKey();
            int sessionMax = 0;
            int userMax = 0;
            for (DraftEvent draft : entry.getValue()) {
                String key = sensitiveKey(draft);
                int sessionCount = eventMapper.countSessionWindow(
                        context.tenantId(), context.userId(), context.sessionId(), key);
                int userCount = properties.isUserWindowEnabled()
                        ? eventMapper.countUserWindow(context.tenantId(), context.userId(), key,
                                properties.windowOf(sensitivityClass))
                        : -1;
                sessionMax = Math.max(sessionMax, sessionCount);
                userMax = Math.max(userMax, userCount);
            }
            result.put(sensitivityClass, new LayerCounts(sensitivityClass, sessionMax, userMax,
                    properties.thresholdOf(sensitivityClass),
                    entry.getValue().get(0)));
        }
        return result;
    }

    /** 首个超阈值层：拒答档（L1 ≥2T 或 L2/L3 ≥T）优先于转审批档（L1 ≥T）。 */
    private ThresholdBreach firstBreach(Map<String, LayerCounts> countsByClass) {
        ThresholdBreach approvalBreach = null;
        for (LayerCounts counts : countsByClass.values()) {
            int effective = Math.max(counts.sessionCount(), counts.userCount());
            if (!properties.isUserWindowEnabled()) {
                // 降级：跳过 user 层计数即强制走审批（fail-safe，见 §7 风险表）
                return new ThresholdBreach(counts.draft(), false);
            }
            if (effective < counts.threshold()) {
                continue;
            }
            boolean blocked = switch (counts.sensitivityClass()) {
                case CLASS_PII -> effective >= 2 * counts.threshold();
                case CLASS_EXISTENCE, CLASS_LABEL -> true;
                default -> false;
            };
            if (blocked) {
                return new ThresholdBreach(counts.draft(), true);
            }
            if (approvalBreach == null) {
                approvalBreach = new ThresholdBreach(counts.draft(), false);
            }
        }
        return approvalBreach;
    }

    /** 同事务创建审批单（§4.8.3：转审批/拒答都建单留痕，拒答仅不放行）。 */
    private SensitiveDecision createApproval(RequestContext context, String requestId,
                                             DraftEvent draft, String auditCode,
                                             LayerCounts counts, String countersDelta) {
        String key = sensitiveKey(draft);
        String scope = scopeHash(context);
        String payload = approvalPayload(requestId, draft, counts, scope);
        String approvalId = approvalService.create(context.tenantId(), context.userId(),
                context.sessionId(), context.turnIndex(), ApprovalService.KIND_SENSITIVE_SEQUENCE,
                payload, auditCode);
        // 放行目标指纹（T2.3）：与决策侧共用同一公式，消费时逐字段比对
        String fingerprint = ApprovalService.fingerprint(context.userId(), context.tenantId(),
                key, scope, draft.filtersHash());
        // HITL 最小通道（T4.3）：事务提交后再下发 INTERRUPT 事件（审批单已确保可见）；
        // 无活动事务同步时（理论不可达——本服务始终在事务模板内）降级为立即下发
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            hitlNotifier.notifyInterrupt(context.tenantId(), context.userId(),
                                    approvalId, auditCode, key);
                        }
                    });
        } else {
            hitlNotifier.notifyInterrupt(context.tenantId(), context.userId(), approvalId,
                    auditCode, key);
        }
        log.info("[recordAndEvaluate][sensitive sequence triggered: tenant={} user={} key={} "
                        + "session={} userWindow={} threshold={} approval={} audit={}]",
                context.tenantId(), context.userId(), key,
                counts == null ? -1 : counts.sessionCount(),
                counts == null ? -1 : counts.userCount(),
                counts == null ? -1 : counts.threshold(),
                approvalId, auditCode);
        return new SensitiveDecision(true, AUDIT_BLOCKED.equals(auditCode)
                || AUDIT_WINDOW_DEGRADED.equals(auditCode), approvalId, auditCode,
                draft.sensitivityClass(), CLASS_PII.equals(draft.sensitivityClass()) ? "L1"
                : CLASS_EXISTENCE.equals(draft.sensitivityClass()) ? "L2" : "L3",
                counts == null ? 0 : counts.sessionCount(),
                counts == null ? 0 : counts.userCount(),
                counts == null ? 0 : counts.threshold(), fingerprint, countersDelta);
    }

    private String approvalPayload(String requestId, DraftEvent draft, LayerCounts counts,
                                   String scopeHash) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("sensitiveKey", sensitiveKey(draft));
        payload.put("sensitivityClass", draft.sensitivityClass());
        payload.put("filtersHash", draft.filtersHash());
        payload.put("resultCardinality", draft.resultCardinality());
        payload.put("scopeHash", scopeHash);
        if (counts != null) {
            payload.put("sessionCount", counts.sessionCount());
            payload.put("userWindowCount", counts.userCount());
            payload.put("threshold", counts.threshold());
        }
        try {
            return JsonUtils.toJsonString(payload);
        } catch (RuntimeException ex) {
            // payload 序列化失败不应让记账事务回滚（审批单仍需落库），降级为最小负载
            log.warn("[approvalPayload][序列化失败，降级最小负载: {}]", ex.toString());
            return "{\"requestId\":\"" + requestId + "\",\"sensitiveKey\":\""
                    + sensitiveKey(draft) + "\"}";
        }
    }

    private static String sensitiveKey(DraftEvent draft) {
        return draft.assetId() + "#" + draft.sensitivityClass();
    }

    private static SensitiveQueryEventDO toRow(RequestContext context, String requestId,
                                               DraftEvent draft, String tag) {
        SensitiveQueryEventDO row = new SensitiveQueryEventDO();
        row.setRequestId(requestId);
        row.setTenantId(context.tenantId());
        row.setUserId(context.userId());
        row.setSessionId(context.sessionId());
        row.setSensitiveKey(sensitiveKey(draft));
        row.setSensitivityClass(draft.sensitivityClass());
        row.setScopeHash(scopeHash(context));
        row.setFiltersHash(draft.filtersHash());
        row.setFiltersCanonical(draft.filtersCanonical());
        row.setResultCardinality(draft.resultCardinality());
        row.setPatternTag(tag);
        row.setReasonCode(null);
        return row;
    }

    /**
     * 授权域哈希（M4 scope = 用户授权全域）：SHA-256(userId|排序角色集)，
     * 与查询缓存键 userScopeHash 同口径（查询侧另行计算传入缓存键，此处独立重算，语义一致）。
     */
    static String scopeHash(RequestContext context) {
        return sha256(context.userId() + "|" + new TreeSet<>(context.roles()));
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static void validateDrafts(List<DraftEvent> drafts) {
        for (DraftEvent draft : drafts) {
            if (draft.assetId() == null || draft.assetId().isBlank()
                    || !List.of(CLASS_PII, CLASS_EXISTENCE, CLASS_LABEL).contains(draft.sensitivityClass())
                    || draft.filtersHash() == null || draft.filtersHash().isBlank()
                    || draft.filtersCanonical() == null
                    || draft.resultCardinality() < 0) {
                throw exception(SENSITIVE_EVENT_INVALID, String.valueOf(draft));
            }
        }
    }

    /**
     * 计数器哈希化增量（§4.8「计数器哈希化后写入审计」）：
     * sha256(JSON{ sensitiveKey: {"s": session计数, "u": user窗口计数} })——
     * 明文计数只在审批 payload 与安全看板口径，事件行 context_delta 与审计行只留哈希。
     */
    static String countersDeltaHash(Map<String, LayerCounts> countsByClass) {
        Map<String, Map<String, Integer>> snapshot = new LinkedHashMap<>();
        for (LayerCounts counts : countsByClass.values()) {
            snapshot.put(sensitiveKey(counts.draft()),
                    Map.of("s", counts.sessionCount(), "u", Math.max(counts.userCount(), 0)));
        }
        return sha256(JsonUtils.toJsonString(snapshot));
    }

    private record InstantTrigger(DraftEvent draft, String auditCode) {
    }

    private record ThresholdBreach(DraftEvent draft, boolean blocked) {
    }

    private record LayerCounts(String sensitivityClass, int sessionCount, int userCount,
                               int threshold, DraftEvent draft) {
    }
}
