import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * PoC #7「跨轮差分与存在性保护」独立探针（M0 / W2 / T2.3）。
 *
 * <p>本探针按技术选型 v1.1 §4.8 规则框架实现一个**参考模型**，用来判定该判据能否成立；
 * 它**不是** M4/M5 的产品实现：没有落库的 {@code sensitive_query_event}、没有接入
 * {@code approval_request}、没有脱敏渲染。探针边界见报告。
 *
 * <p>判据见技术选型 §7 #7；本探针只输出实测，不复制阈值。
 *
 * <p>运行：java scripts/poc/Poc07CrossRoundDifferential.java &lt;输出 JSON 路径&gt;
 */
public class Poc07CrossRoundDifferential {

    /** 差分对的基数差阈值 k（§4.8.2 建议初值）。 */
    private static final int K = 5;

    private static final int TRIALS_PER_ATTACK = 25;
    private static final int NORMAL_USERS = 50;
    private static final int NORMAL_ROUNDS = 4;
    private static final int NORMAL_SAMPLES = NORMAL_USERS * NORMAL_ROUNDS;

    /** 对外统一文案：拦截时不得泄露是哪个条件先触发（§4.8.3 末段）。 */
    private static final String BLOCK_MESSAGE = "该查询暂时无法返回，如需继续请提交审批申请。";

    /** 所有实际下发的拦截文案，用于验证「文案差异信息泄露 = 0」。 */
    private static final Set<String> BLOCK_MESSAGES = new LinkedHashSet<>();

    // ------------------------------------------------------------------ 领域模型

    enum Level { L1, L2, L3 }

    enum Pattern {
        PLAIN(1),
        NARROWING(2),
        DIFFERENTIAL(3),
        EXISTENCE_PROBE(3);

        final int weight;

        Pattern(int weight) {
            this.weight = weight;
        }
    }

    enum Verdict { ALLOW, APPROVAL, REFUSE, HARD_BLOCK }

    /** 规则档位：baseline = §4.8 建议初值；lowered = 仅下调阈值；hardened = 精确差分即时动作。 */
    enum Profile {
        BASELINE("建议初值：T(L1)=8 / T(L2)=4 / T(L3)=3，差分对只计累计权重", 8, false),
        LOWERED_T("退路 A：仅把 T(L1) 下调到 4，其余不变", 4, false),
        HARDENED("退路 B：T(L1) 保持 8，精确差分命中即转审批", 8, true);

        final String description;
        final int l1Threshold;
        final boolean preciseDifferentialImmediate;

        Profile(String description, int l1Threshold, boolean preciseDifferentialImmediate) {
            this.description = description;
            this.l1Threshold = l1Threshold;
            this.preciseDifferentialImmediate = preciseDifferentialImmediate;
        }
    }

    record Customer(int id, String region, String channel, boolean vip, BigDecimal salary) {
    }

    record Outcome(Verdict verdict, String reasonCode, String message, int sessionRisk, int userRisk) {
        boolean blocked() {
            return verdict != Verdict.ALLOW;
        }
    }

    record Event(Pattern pattern, int weight, Instant occurredAt, Map<String, String> filters,
                 Set<Integer> matchedIds, String entityId) {
    }

    record Request(String tenantId, String userId, String sessionId, String sensitiveKey, Level level,
                   Map<String, String> filters, String entityId) {
    }

    record TrialResult(String attack, int trial, boolean leaked, boolean intercepted, String reasonCode,
                       int steps, String layer) {
    }

    record Stats(String name, int trials, int leaked, int intercepted, int reasonCodeHits, int hardBlocks,
                 int blockedByUserLayer) {
    }

    // ------------------------------------------------------------------ 受保护数据源（模拟）

    static final class Dataset {

        final List<Customer> customers = new ArrayList<>();

        Dataset(long seed) {
            Random random = new Random(seed);
            String[] regions = {"华东", "华南", "华北"};
            String[] channels = {"线上", "线下"};
            int id = 1;
            for (String region : regions) {
                for (String channel : channels) {
                    for (int i = 0; i < 20; i++) {
                        boolean vip = i == 0; // 每组 20 人，恰好 1 个 VIP
                        customers.add(new Customer(id++, region, channel, vip,
                                BigDecimal.valueOf(8_000 + random.nextInt(22_000))));
                    }
                }
            }
        }

        Set<Integer> match(Map<String, String> filters) {
            Set<Integer> ids = new TreeSet<>();
            for (Customer customer : customers) {
                if (matches(customer, filters)) {
                    ids.add(customer.id());
                }
            }
            return ids;
        }

        private static boolean matches(Customer customer, Map<String, String> filters) {
            for (Map.Entry<String, String> entry : filters.entrySet()) {
                boolean ok = switch (entry.getKey()) {
                    case "region" -> customer.region().equals(entry.getValue());
                    case "channel" -> customer.channel().equals(entry.getValue());
                    case "tier" -> customer.vip() == "VIP".equals(entry.getValue());
                    case "customer" -> String.valueOf(customer.id()).equals(entry.getValue());
                    default -> false;
                };
                if (!ok) {
                    return false;
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ 规则引擎

    static final class Guard {

        private final Dataset dataset;
        private final Profile profile;

        /** session 层：tenant|user|session|key → 事件序列。 */
        private final Map<String, List<Event>> sessionEvents = new HashMap<>();
        /** user+tenant 层：tenant|user|key → 事件序列，跨会话不清空。 */
        private final Map<String, List<Event>> userEvents = new HashMap<>();

        Guard(Dataset dataset, Profile profile) {
            this.dataset = dataset;
            this.profile = profile;
        }

        private static String sessionKey(Request request) {
            return String.join("|", request.tenantId(), request.userId(), request.sessionId(),
                    request.sensitiveKey());
        }

        private static String userKey(Request request) {
            return String.join("|", request.tenantId(), request.userId(), request.sensitiveKey());
        }

        int threshold(Level level) {
            return thresholdOf(profile, level);
        }

        private static Duration window(Level level) {
            return level == Level.L3 ? Duration.ofDays(7) : Duration.ofHours(24);
        }

        Outcome evaluate(Request request) {
            Instant now = Instant.now();
            Set<Integer> matched = dataset.match(request.filters());

            List<Event> sessionHistory = sessionEvents.getOrDefault(sessionKey(request), List.of());
            List<Event> userHistory = userEvents.getOrDefault(userKey(request), List.of());

            Pattern sessionPattern = classify(sessionHistory, request, matched);
            Pattern userPattern = classify(userHistory, request, matched);

            // 两层各自独立计算 risk：session 窗口为会话生命周期，user 窗口按 sensitivity_class 滑动
            int sessionRisk = riskOf(sessionHistory, null) + sessionPattern.weight;
            int userRisk = riskOf(userHistory, window(request.level())) + userPattern.weight;

            Outcome outcome = decide(request, matched, sessionPattern, userPattern, sessionRisk, userRisk);
            if (outcome.blocked()) {
                BLOCK_MESSAGES.add(outcome.message());
            }

            // 被拒 / 硬阻断的请求并未执行，不计入后续风险累计
            if (outcome.verdict() == Verdict.ALLOW || outcome.verdict() == Verdict.APPROVAL) {
                sessionEvents.computeIfAbsent(sessionKey(request), key -> new ArrayList<>())
                        .add(new Event(sessionPattern, sessionPattern.weight, now, request.filters(), matched,
                                request.entityId()));
                userEvents.computeIfAbsent(userKey(request), key -> new ArrayList<>())
                        .add(new Event(userPattern, userPattern.weight, now, request.filters(), matched,
                                request.entityId()));
            }
            return outcome;
        }

        private Outcome decide(Request request, Set<Integer> matched, Pattern sessionPattern,
                               Pattern userPattern, int sessionRisk, int userRisk) {
            // 1. 存在性探测：硬阻断，不进入审批（§4.8.3 末行）
            if (sessionPattern == Pattern.EXISTENCE_PROBE || userPattern == Pattern.EXISTENCE_PROBE) {
                return new Outcome(Verdict.HARD_BLOCK, "SECURITY_EXISTENCE_PROBE", BLOCK_MESSAGE,
                        sessionRisk, userRisk);
            }
            // 2. L1 个体值访问一律转审批，不直接返回
            if (request.level() == Level.L1 && matched.size() <= 1) {
                return new Outcome(Verdict.APPROVAL, "SENSITIVE_QUERY_SEQUENCE", BLOCK_MESSAGE,
                        sessionRisk, userRisk);
            }
            // 3. 修订档：精确差分（两轮内可反推个体值）命中即转审批
            if (profile.preciseDifferentialImmediate && request.level() == Level.L1
                    && isPreciseDifferential(request, matched)) {
                return new Outcome(Verdict.APPROVAL, "SENSITIVE_QUERY_SEQUENCE", BLOCK_MESSAGE,
                        sessionRisk, userRisk);
            }
            // 4. 累计风险：两层各与 T 比较，不累加，任一超阈值即触发动作
            int threshold = threshold(request.level());
            boolean sessionOver = sessionRisk >= threshold;
            boolean userOver = userRisk >= threshold;
            if (!sessionOver && !userOver) {
                return new Outcome(Verdict.ALLOW, null, null, sessionRisk, userRisk);
            }
            if (request.level() == Level.L1) {
                boolean doubled = sessionRisk >= threshold * 2 || userRisk >= threshold * 2;
                return new Outcome(doubled ? Verdict.REFUSE : Verdict.APPROVAL,
                        "SENSITIVE_QUERY_SEQUENCE", BLOCK_MESSAGE, sessionRisk, userRisk);
            }
            return new Outcome(Verdict.REFUSE, "SENSITIVE_QUERY_SEQUENCE", BLOCK_MESSAGE,
                    sessionRisk, userRisk);
        }

        /** 与上一条放行记录构成「基数差 1 且结果集包含」的精确差分。 */
        private boolean isPreciseDifferential(Request request, Set<Integer> matched) {
            List<Event> history = userEvents.getOrDefault(userKey(request), List.of());
            if (history.isEmpty()) {
                return false;
            }
            Event last = history.get(history.size() - 1);
            if (diffFilters(request.filters(), last.filters()) != 1) {
                return false;
            }
            Set<Integer> previous = last.matchedIds();
            if (previous.size() <= 1 || matched.size() <= 1) {
                return false;
            }
            boolean nested = previous.containsAll(matched) || matched.containsAll(previous);
            return nested && Math.abs(previous.size() - matched.size()) == 1;
        }

        private static int riskOf(List<Event> history, Duration window) {
            Instant cutoff = window == null ? null : Instant.now().minus(window);
            return history.stream()
                    .filter(event -> cutoff == null || event.occurredAt().isAfter(cutoff))
                    .mapToInt(Event::weight)
                    .sum();
        }

        /** 逐请求打 pattern_tag，每请求只取最高权重（§4.8.2）。 */
        private Pattern classify(List<Event> history, Request request, Set<Integer> matched) {
            if (history.isEmpty()) {
                return Pattern.PLAIN;
            }
            Event last = history.get(history.size() - 1);

            // 存在性探测：同一 sensitive_key 换实体标识重复请求，结果统一「不可用」
            if (request.level() == Level.L2
                    && history.stream().anyMatch(event -> !Objects.equals(event.entityId(), request.entityId()))) {
                return Pattern.EXISTENCE_PROBE;
            }

            int filterDiff = diffFilters(request.filters(), last.filters());
            int cardinalityDelta = Math.abs(matched.size() - last.matchedIds().size());
            boolean nested = matched.containsAll(last.matchedIds()) || last.matchedIds().containsAll(matched);

            // 差分对：条件差 1、基数差 < k，且结果集存在包含关系（否则结果不相交，差不出个体值）
            if (filterDiff == 1 && cardinalityDelta < K && nested) {
                return Pattern.DIFFERENTIAL;
            }
            // 逐步收窄：相邻两次仅变更一个条件且基数非增
            if (filterDiff == 1 && matched.size() <= last.matchedIds().size()) {
                return Pattern.NARROWING;
            }
            // 连续 ≥3 次单调收窄：要求窗口内基数严格下降，否则「基数不变、条件反复变」的
            // 正常多轮分析会被误判为收窄（§7 #7 的 ≤2% 误报率要求排除这种解释）
            if (history.size() >= 2) {
                Event previous = history.get(history.size() - 2);
                boolean monotone = previous.matchedIds().size() >= last.matchedIds().size()
                        && last.matchedIds().size() >= matched.size()
                        && previous.matchedIds().size() > matched.size()
                        && (previous.matchedIds().containsAll(matched) || last.matchedIds().containsAll(matched))
                        && !previous.filters().equals(last.filters())
                        && !last.filters().equals(request.filters());
                if (monotone) {
                    return Pattern.NARROWING;
                }
            }
            return Pattern.PLAIN;
        }

        private static int diffFilters(Map<String, String> left, Map<String, String> right) {
            Set<String> keys = new TreeSet<>(left.keySet());
            keys.addAll(right.keySet());
            int diff = 0;
            for (String key : keys) {
                if (!Objects.equals(left.get(key), right.get(key))) {
                    diff++;
                }
            }
            return diff;
        }
    }

    // ------------------------------------------------------------------ 攻击序列与正常样本

    static Map<String, String> filters(String... pairs) {
        Map<String, String> map = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    static String regionOf(int trial) {
        return switch (trial % 3) {
            case 0 -> "华东";
            case 1 -> "华南";
            default -> "华北";
        };
    }

    static String channelOf(int trial) {
        return trial % 2 == 0 ? "线上" : "线下";
    }

    static int thresholdOf(Profile profile, Level level) {
        return switch (level) {
            case L1 -> profile.l1Threshold;
            case L2 -> 4;
            case L3 -> 3;
        };
    }

    static String layerOf(Outcome outcome, Level level, Profile profile) {
        int threshold = thresholdOf(profile, level);
        boolean sessionOver = outcome.sessionRisk() >= threshold;
        boolean userOver = outcome.userRisk() >= threshold;
        if (sessionOver && userOver) {
            return "session+user";
        }
        if (sessionOver) {
            return "session";
        }
        return userOver ? "user+tenant" : "rule";
    }

    /** 逐步收窄：region → +channel → +tier(普通) → +tier(VIP)。 */
    static TrialResult narrowingTrial(Dataset dataset, Profile profile, int trial) {
        Guard guard = new Guard(dataset, profile);
        String region = regionOf(trial);
        String channel = channelOf(trial);
        List<Map<String, String>> steps = List.of(
                filters("region", region),
                filters("region", region, "channel", channel),
                filters("region", region, "channel", channel, "tier", "普通"),
                filters("region", region, "channel", channel, "tier", "VIP"));

        Set<Integer> previousIds = null;
        for (int index = 0; index < steps.size(); index++) {
            Map<String, String> step = steps.get(index);
            Set<Integer> ids = dataset.match(step);
            boolean leakAfterAllow = previousIds != null
                    && (previousIds.containsAll(ids) || ids.containsAll(previousIds))
                    && Math.abs(previousIds.size() - ids.size()) == 1;
            Outcome outcome = guard.evaluate(new Request("t1", "u-n" + trial, "s-n" + trial,
                    "customer#salary", Level.L1, step, null));
            if (outcome.blocked()) {
                return new TrialResult("narrowing_sequence", trial, false, true, outcome.reasonCode(),
                        index + 1, layerOf(outcome, Level.L1, profile));
            }
            if (leakAfterAllow) {
                return new TrialResult("narrowing_sequence", trial, true, false, null, index + 1, "none");
            }
            previousIds = ids;
        }
        return new TrialResult("narrowing_sequence", trial, false, false, null, steps.size(), "none");
    }

    /** 差分对：两次查询仅差 tier 一个条件，基数 20 → 19。 */
    static TrialResult differentialTrial(Dataset dataset, Profile profile, int trial) {
        Guard guard = new Guard(dataset, profile);
        String region = regionOf(trial);
        String channel = channelOf(trial);
        Map<String, String> base = filters("region", region, "channel", channel);
        Map<String, String> narrowed = filters("region", region, "channel", channel, "tier", "普通");

        Outcome first = guard.evaluate(new Request("t1", "u-d" + trial, "s-d" + trial, "customer#salary",
                Level.L1, base, null));
        if (first.blocked()) {
            return new TrialResult("differential_pair", trial, false, true, first.reasonCode(), 1,
                    layerOf(first, Level.L1, profile));
        }
        Outcome second = guard.evaluate(new Request("t1", "u-d" + trial, "s-d" + trial, "customer#salary",
                Level.L1, narrowed, null));
        if (second.blocked()) {
            return new TrialResult("differential_pair", trial, false, true, second.reasonCode(), 2,
                    layerOf(second, Level.L1, profile));
        }
        return new TrialResult("differential_pair", trial, true, false, null, 2, "none");
    }

    /** 跨会话规避：每轮新 session_id，同一 user 只发普通敏感聚合查询。 */
    static TrialResult crossSessionTrial(Dataset dataset, Profile profile, int trial) {
        Guard guard = new Guard(dataset, profile);
        Map<String, String> condition = filters("region", regionOf(trial), "channel", channelOf(trial));
        for (int round = 1; round <= 12; round++) {
            Outcome outcome = guard.evaluate(new Request("t1", "u-c" + trial, "s-" + round,
                    "customer#salary", Level.L1, condition, null));
            if (outcome.blocked()) {
                return new TrialResult("cross_session_evasion", trial, false, true, outcome.reasonCode(),
                        round, layerOf(outcome, Level.L1, profile));
            }
        }
        return new TrialResult("cross_session_evasion", trial, false, false, null, 12, "none");
    }

    /** 批量存在性探测：同一 key 换不同实体标识，数据源统一返回「不可用」。 */
    static TrialResult existenceTrial(Dataset dataset, Profile profile, int trial) {
        Guard guard = new Guard(dataset, profile);
        for (int index = 0; index < 7; index++) {
            String entity = "probe" + trial + "-" + index;
            Outcome outcome = guard.evaluate(new Request("t1", "u-e" + trial, "s-e" + trial,
                    "customer.email#existence", Level.L2, filters("email", entity + "@example.com"), entity));
            if (outcome.blocked()) {
                boolean hardBlock = outcome.verdict() == Verdict.HARD_BLOCK;
                return new TrialResult("existence_probe", trial, !hardBlock, hardBlock, outcome.reasonCode(),
                        index + 1, layerOf(outcome, Level.L2, profile));
            }
        }
        return new TrialResult("existence_probe", trial, false, false, null, 7, "none");
    }

    /** 正常业务：50 个用户各 4 轮，切换互不重叠的分组（非收窄、非差分）。 */
    static int normalFalsePositives(Dataset dataset, Profile profile) {
        String[][] conditions = {
                {"region", "华东", "channel", "线上"},
                {"region", "华南", "channel", "线下"},
                {"region", "华北", "channel", "线上"},
                {"region", "华东", "channel", "线下"},
        };
        int falsePositives = 0;
        for (int user = 0; user < NORMAL_USERS; user++) {
            Guard guard = new Guard(dataset, profile);
            for (String[] condition : conditions) {
                Outcome outcome = guard.evaluate(new Request("t1", "normal-" + user, "s-" + user,
                        "customer#salary", Level.L1, filters(condition), null));
                if (outcome.blocked()) {
                    falsePositives++;
                }
            }
        }
        return falsePositives;
    }

    /**
     * 两层不累加：同一会话 5 次普通请求，session 与 user 两层 risk 各为 5、和为 10 ≥ T=8。
     * 独立判定应全部放行；若实现把两层相加，第 5 次就会被拦。
     */
    static boolean twoLayersNotAccumulated(Dataset dataset) {
        Guard guard = new Guard(dataset, Profile.BASELINE);
        Map<String, String> condition = filters("region", "华东", "channel", "线上");
        for (int i = 0; i < 5; i++) {
            Outcome outcome = guard.evaluate(new Request("t1", "u-acc", "s-acc", "customer#salary",
                    Level.L1, condition, null));
            if (outcome.blocked()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 窗口滑动：4 次请求（risk=4）后注入一条 25 小时前、权重 3 的事件。
     * 过期生效则第 5 次 risk=5 放行；过期失效则 risk=8 被拦。
     */
    static boolean windowExpiryWorks(Dataset dataset) {
        Guard guard = new Guard(dataset, Profile.BASELINE);
        Map<String, String> condition = filters("region", "华东", "channel", "线上");
        Request request = new Request("t1", "u-exp", "s-exp", "customer#salary", Level.L1, condition, null);
        for (int i = 0; i < 4; i++) {
            guard.evaluate(request);
        }
        guard.userEvents.computeIfAbsent("t1|u-exp|customer#salary", key -> new ArrayList<>())
                .add(new Event(Pattern.DIFFERENTIAL, 3, Instant.now().minus(Duration.ofHours(25)), condition,
                        dataset.match(condition), null));
        return !guard.evaluate(request).blocked();
    }

    // ------------------------------------------------------------------ 统计与输出

    static Stats summarize(String name, List<TrialResult> results) {
        return new Stats(
                name,
                results.size(),
                (int) results.stream().filter(TrialResult::leaked).count(),
                (int) results.stream().filter(TrialResult::intercepted).count(),
                (int) results.stream().filter(r -> "SENSITIVE_QUERY_SEQUENCE".equals(r.reasonCode())).count(),
                (int) results.stream().filter(r -> "SECURITY_EXISTENCE_PROBE".equals(r.reasonCode())).count(),
                (int) results.stream().filter(r -> "user+tenant".equals(r.layer())).count());
    }

    record ProfileReport(Profile profile, Stats narrowing, Stats differential, Stats crossSession,
                         Stats existence, int normalTotal, int normalFalsePositives, boolean notAccumulated,
                         boolean expiryWorks, int distinctBlockMessages, boolean pass, List<String> failed) {
    }

    static ProfileReport runProfile(Dataset dataset, Profile profile) {
        List<TrialResult> narrowing = new ArrayList<>();
        List<TrialResult> differential = new ArrayList<>();
        List<TrialResult> crossSession = new ArrayList<>();
        List<TrialResult> existence = new ArrayList<>();
        for (int trial = 0; trial < TRIALS_PER_ATTACK; trial++) {
            narrowing.add(narrowingTrial(dataset, profile, trial));
            differential.add(differentialTrial(dataset, profile, trial));
            crossSession.add(crossSessionTrial(dataset, profile, trial));
            existence.add(existenceTrial(dataset, profile, trial));
        }
        int falsePositives = normalFalsePositives(dataset, profile);
        boolean notAccumulated = twoLayersNotAccumulated(dataset);
        boolean expiryWorks = windowExpiryWorks(dataset);

        Stats narrowingStats = summarize("narrowing_sequence", narrowing);
        Stats differentialStats = summarize("differential_pair", differential);
        Stats crossStats = summarize("cross_session_evasion", crossSession);
        Stats existenceStats = summarize("existence_probe", existence);

        List<String> failed = new ArrayList<>();
        int leaked = narrowingStats.leaked() + differentialStats.leaked()
                + crossStats.leaked() + existenceStats.leaked();
        if (leaked != 0) {
            failed.add("跨轮差分泄露成功 " + leaked + " 次（要求 0）");
        }
        if (crossStats.intercepted() != crossStats.trials()) {
            failed.add("跨会话规避拦截率 " + crossStats.intercepted() + "/" + crossStats.trials() + "（要求 100%）");
        }
        int cumulativeTrials = narrowingStats.trials() + differentialStats.trials() + crossStats.trials();
        int reasonHits = narrowingStats.reasonCodeHits() + differentialStats.reasonCodeHits()
                + crossStats.reasonCodeHits();
        if (reasonHits != cumulativeTrials) {
            failed.add("SENSITIVE_QUERY_SEQUENCE 命中率 " + reasonHits + "/" + cumulativeTrials + "（要求 100%）");
        }
        double falsePositiveRate = (double) falsePositives / NORMAL_SAMPLES;
        if (falsePositiveRate > 0.02) {
            failed.add(String.format("正常样本误报率 %.2f%%（要求 ≤2%%）", falsePositiveRate * 100));
        }
        if (existenceStats.hardBlocks() != existenceStats.trials()) {
            failed.add("存在性探测硬阻断率 " + existenceStats.hardBlocks() + "/" + existenceStats.trials()
                    + "（要求 100%）");
        }
        if (!notAccumulated) {
            failed.add("两层风险被累加（5 次普通请求即被拦截，应放行）");
        }
        if (!expiryWorks) {
            failed.add("窗口外事件仍计入风险");
        }
        if (BLOCK_MESSAGES.size() != 1) {
            failed.add("对外拦截文案出现 " + BLOCK_MESSAGES.size() + " 种（要求 1，差异信息泄露 0）");
        }

        return new ProfileReport(profile, narrowingStats, differentialStats, crossStats, existenceStats,
                NORMAL_SAMPLES, falsePositives, notAccumulated, expiryWorks, BLOCK_MESSAGES.size(),
                failed.isEmpty(), failed);
    }

    static String jsonEscape(String value) {
        StringBuilder builder = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return jsonEscape(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> jsonEscape(String.valueOf(entry.getKey())) + ":" + json(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(',');
                }
                builder.append(json(item));
                first = false;
            }
            return builder.append(']').toString();
        }
        return jsonEscape(value.toString());
    }

    static Map<String, Object> attackJson(Stats stats) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("trials", stats.trials());
        map.put("leaked", stats.leaked());
        map.put("intercepted", stats.intercepted());
        map.put("reasonCodeHits", stats.reasonCodeHits());
        map.put("hardBlocks", stats.hardBlocks());
        map.put("blockedByUserLayer", stats.blockedByUserLayer());
        return map;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法：java Poc07CrossRoundDifferential.java <输出 JSON 路径>");
            System.exit(2);
        }
        Dataset dataset = new Dataset(20261107L);
        List<ProfileReport> reports = List.of(
                runProfile(dataset, Profile.BASELINE),
                runProfile(dataset, Profile.LOWERED_T),
                runProfile(dataset, Profile.HARDENED));

        Map<String, Object> profiles = new LinkedHashMap<>();
        for (ProfileReport report : reports) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("description", report.profile().description);
            Map<String, Object> attacks = new LinkedHashMap<>();
            attacks.put("narrowing_sequence", attackJson(report.narrowing()));
            attacks.put("differential_pair", attackJson(report.differential()));
            attacks.put("cross_session_evasion", attackJson(report.crossSession()));
            attacks.put("existence_probe", attackJson(report.existence()));
            entry.put("attacks", attacks);
            Map<String, Object> normal = new LinkedHashMap<>();
            normal.put("samples", report.normalTotal());
            normal.put("falsePositives", report.normalFalsePositives());
            normal.put("falsePositiveRate",
                    String.format("%.2f%%", 100.0 * report.normalFalsePositives() / report.normalTotal()));
            entry.put("normal", normal);
            Map<String, Object> checks = new LinkedHashMap<>();
            checks.put("twoLayersNotAccumulated", report.notAccumulated());
            checks.put("windowExpiryWorks", report.expiryWorks());
            checks.put("distinctBlockMessages", report.distinctBlockMessages());
            entry.put("checks", checks);
            entry.put("verdict", report.pass() ? "PASS" : "FAIL");
            entry.put("failedCriteria", report.failed());
            profiles.put(report.profile().name(), entry);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("poc", "#7 跨轮差分与存在性保护");
        root.put("task", "M0/T2.3");
        root.put("level", "探针级（参考模型，非产品实现）");
        root.put("executedAt", Instant.now().toString());
        root.put("java", System.getProperty("java.version"));
        Map<String, Object> datasetInfo = new LinkedHashMap<>();
        datasetInfo.put("customers", dataset.customers.size());
        datasetInfo.put("groups", 6);
        datasetInfo.put("perGroup", 20);
        datasetInfo.put("vipPerGroup", 1);
        root.put("dataset", datasetInfo);
        Map<String, Object> constants = new LinkedHashMap<>();
        constants.put("k", K);
        constants.put("trialsPerAttack", TRIALS_PER_ATTACK);
        constants.put("normalSamples", NORMAL_SAMPLES);
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("L1", 8);
        thresholds.put("L2", 4);
        thresholds.put("L3", 3);
        constants.put("thresholds", thresholds);
        root.put("constants", constants);
        root.put("profiles", profiles);

        String output = json(root);
        try {
            Path path = Path.of(args[0]);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, output + "\n");
        } catch (Exception ex) {
            System.err.println("写文件失败：" + ex.getMessage());
            System.out.println(output);
        }

        System.out.println("PoC #7 结果（" + dataset.customers.size() + " 行模拟数据集，"
                + TRIALS_PER_ATTACK + " trial/类型，正常样本 " + NORMAL_SAMPLES + "）");
        for (ProfileReport report : reports) {
            System.out.printf("%n[%s]%n  %s%n", report.profile().name(), report.profile().description);
            printAttack("逐步收窄  ", report.narrowing());
            printAttack("差分对    ", report.differential());
            printAttack("跨会话规避", report.crossSession());
            printAttack("存在性探测", report.existence());
            System.out.printf("  正常样本     误报 %d/%d（%.2f%%）%n", report.normalFalsePositives(),
                    report.normalTotal(), 100.0 * report.normalFalsePositives() / report.normalTotal());
            System.out.printf("  两层不累加 %s，窗口过期 %s，拦截文案种类 %d%n",
                    report.notAccumulated(), report.expiryWorks(), report.distinctBlockMessages());
            System.out.printf("  结论：%s%n", report.pass() ? "PASS" : "FAIL");
            for (String failure : report.failed()) {
                System.out.println("    - " + failure);
            }
        }
    }

    static void printAttack(String label, Stats stats) {
        System.out.printf("  %s  泄露 %d/%d，拦截 %d/%d，原因码命中 %d，硬阻断 %d%n",
                label, stats.leaked(), stats.trials(), stats.intercepted(), stats.trials(),
                stats.reasonCodeHits(), stats.hardBlocks());
    }
}
