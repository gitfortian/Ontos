package com.ontos.module.query.core.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;

/**
 * 相对时间解析器（M2/T2.3，ADR-0004 2.4 口径）：
 * 消费发布清单的 timeExpressionPattern 资产，把「近 N 天 / 上个月 / 今年」等相对表达
 * 换算为 resolved_range（左闭右开 [from, to)），时区固定 Asia/Shanghai。
 *
 * <p>口径（ADR-0004 2.4）：左闭右开避免跨日重复计数；「近 N 天」**含当天**，
 * 即 [today-N+1, tomorrow)；解析器版本随清单冻结，版本号进审计保证可回放。
 * 未命中任何模式 → 返回 empty（调用方澄清/拒绝），**禁止猜测**。
 *
 * <p>LLM 不产出绝对日期（硬性边界 3）：绝对区间仅来自用户显式输入的 explicit_range，
 * 由 DSL 校验器按来源标记放行，不经本解析器。
 */
public class TimeExpressionParser {

    public static final String ZONE = "Asia/Shanghai";
    /** 解析器版本：模式集来自清单（timeExpressionPatterns），规则实现变更时升版。 */
    public static final String PARSER_VERSION = "time-parser-1.0";

    private static final ZoneId ZONE_ID = ZoneId.of(ZONE);

    public record ResolvedRange(Instant from, Instant to, String patternId, String version) {
    }

    public record ParseResult(ResolvedRange resolved, String failReason) {
    }

    private final List<ManifestPayload.TimeExpressionPattern> patterns;

    public TimeExpressionParser(PublishedManifest manifest) {
        this.patterns = manifest == null || manifest.getTimeExpressionPatterns() == null
                ? List.of()
                : manifest.getTimeExpressionPatterns();
    }

    /** 便捷构造：直接传模式集（测试用）。 */
    public TimeExpressionParser(List<ManifestPayload.TimeExpressionPattern> patterns) {
        this.patterns = patterns == null ? List.of() : patterns;
    }

    /** 解析相对时间表达；未命中清单模式返回 failReason=「未命中模式」。 */
    public ParseResult parse(String raw, LocalDate today) {
        if (raw == null || raw.isBlank()) {
            return new ParseResult(null, "时间表达为空");
        }
        String normalized = raw.trim();
        for (ManifestPayload.TimeExpressionPattern pattern : patterns) {
            Instant[] range = tryPattern(pattern, normalized, today);
            if (range != null) {
                return new ParseResult(new ResolvedRange(range[0], range[1],
                        pattern.getPatternId(), PARSER_VERSION), null);
            }
        }
        return new ParseResult(null, "未命中任何已发布时间模式: " + normalized);
    }

    /** 模式匹配：pattern 中「N」为数字占位（如「近 N 天」），其余按字面匹配。 */
    private Instant[] tryPattern(ManifestPayload.TimeExpressionPattern pattern,
                                 String normalized, LocalDate today) {
        String shape = pattern.getPattern();
        if (shape == null) {
            return null;
        }
        if (shape.contains("N")) {
            Matcher matcher = Pattern.compile(numericPattern(shape)).matcher(normalized);
            if (!matcher.matches()) {
                return null;
            }
            int n = Integer.parseInt(matcher.group(1));
            if (n <= 0) {
                return null;
            }
            return switch (pattern.getGranularity()) {
                // 「近 N 天」含当天：[today-N+1, today+1) 恰为 N 天（ADR-0004 2.4）
                case "DAY" -> dayRange(today.minusDays(n - 1L), today.plusDays(1));
                case "MONTH" -> monthRange(today.minusMonths(n - 1L), n);
                default -> null;
            };
        }
        if (!shape.equals(normalized)) {
            return null;
        }
        return switch (pattern.getPatternId()) {
            case "tep.last_month" -> monthRange(today.minusMonths(1), 1);
            case "tep.this_month" -> monthRange(today, 1);
            default -> switch (pattern.getGranularity()) {
                case "YEAR" -> yearRange(today);
                case "QUARTER" -> quarterRange(today);
                default -> null;
            };
        };
    }

    /**
     * 构造含数字占位的匹配正则：按 N 分段，各段分别 quote（字面），
     * N 位置插入捕获组——顺序是「先拆分再 quote」，不能先 quote 整串再替换
     * （\Q..\E 会把 (\d+) 也变成字面文本，正则永远匹配不上数字）。
     */
    private static String numericPattern(String shape) {
        String[] parts = shape.split("N", -1);
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append("(\\d+)");
            }
            regex.append(Pattern.quote(parts[i]));
        }
        return regex.append("$").toString();
    }

    /** 左闭右开日区间。 */
    private static Instant[] dayRange(LocalDate from, LocalDate toExclusive) {
        return new Instant[] {
            from.atStartOfDay(ZONE_ID).toInstant(),
            toExclusive.atStartOfDay(ZONE_ID).toInstant(),
        };
    }

    /** 从某月起的连续 n 个月，左闭右开。 */
    private static Instant[] monthRange(LocalDate firstMonth, int months) {
        LocalDate from = firstMonth.withDayOfMonth(1);
        LocalDate toExclusive = from.plusMonths(months);
        return new Instant[] {
            from.atStartOfDay(ZONE_ID).toInstant(),
            toExclusive.atStartOfDay(ZONE_ID).toInstant(),
        };
    }

    private static Instant[] yearRange(LocalDate today) {
        LocalDate from = today.withDayOfYear(1);
        return new Instant[] {
            from.atStartOfDay(ZONE_ID).toInstant(),
            from.plusYears(1).atStartOfDay(ZONE_ID).toInstant(),
        };
    }

    private static Instant[] quarterRange(LocalDate today) {
        int quarterStart = ((today.getMonthValue() - 1) / 3) * 3 + 1;
        LocalDate from = LocalDate.of(today.getYear(), quarterStart, 1);
        return new Instant[] {
            from.atStartOfDay(ZONE_ID).toInstant(),
            from.plusMonths(3).atStartOfDay(ZONE_ID).toInstant(),
        };
    }

}
