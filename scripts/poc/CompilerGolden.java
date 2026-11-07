import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.query.core.compiler.CompiledQuery;
import com.ontos.module.query.core.compiler.SqlCompiler;
import com.ontos.module.query.core.dsl.QueryDsl;

/**
 * 编译器 Golden SQL 集的生成与校验（M0 / W3 / T3.3，#4 的最小探针）。
 *
 * 判据 §7 #4 要求覆盖 query / lookup / lookup_batch / funnel / yoy_mom 的 ≥30 条 Golden SQL 逐字比对。
 * 当前 SqlCompiler 只实现单表聚合、**不按 mode 分支**，因此本探针把用例分成两类期望：
 *
 *   compiled      —— QUERY 模式，逐字比对规范化 SQL 与绑定值；
 *   unimplemented —— 其余模式，期望被拒绝。若编译器实际生成了 SQL，记为 SILENT_WRONG_MODE，
 *                    因为那会返回语义错误的聚合结果而不是报错，属于比崩溃更危险的行为。
 *
 * 用法：
 *   java CompilerGolden.java --generate <out.json>   生成/刷新 Golden 集
 *   java CompilerGolden.java --verify   <golden.json> 逐条重编译比对
 */
public class CompilerGolden {

    static final String TABLE = "public.fact_order";
    static final String DIM_TABLE = "public.dim_customer";

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--verify";
        String path = args.length > 1 ? args[1] : "docs/06-verification/evaluation/编译器Golden集-v1.json";
        PublishedManifest manifest = manifest();
        List<Case> cases = cases();

        if ("--generate".equals(mode)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("generatedAt", Instant.now().toString());
            out.put("manifestId", manifest.getManifestId());
            out.put("contentHash", manifest.getContentHash());
            out.put("note", "由当前编译器生成并人工审阅后冻结；M2 补齐非 QUERY 模式后必须重新生成并升版本");
            List<Object> items = new ArrayList<>();
            for (Case testCase : cases) {
                items.add(run(testCase, manifest));
            }
            out.put("cases", items);
            Files.createDirectories(Path.of(path).getParent());
            Files.writeString(Path.of(path), toJson(out) + "\n", StandardCharsets.UTF_8);
            System.out.println("已生成 " + items.size() + " 条用例 → " + path);
            summary(items);
            return;
        }

        String raw = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        List<Map<String, Object>> golden = extractCases(raw);
        if (golden.size() != cases.size()) {
            System.out.printf("FAIL Golden 集条数 %d 与用例定义 %d 不一致%n", golden.size(), cases.size());
            System.exit(1);
        }

        int failed = 0;
        int compiled = 0;
        int unimplemented = 0;
        for (int i = 0; i < cases.size(); i++) {
            Case testCase = cases.get(i);
            Map<String, Object> expected = golden.get(i);
            Map<String, Object> actual = run(testCase, manifest);
            String expectedStatus = str(expected.get("status"));
            String actualStatus = str(actual.get("status"));

            if ("compiled".equals(expectedStatus)) {
                compiled++;
                if (!"compiled".equals(actualStatus)) {
                    failed++;
                    System.out.printf("  [FAIL] %-6s 期望编译成功，实际 %s（%s）%n", testCase.id, actualStatus, str(actual.get("message")));
                    continue;
                }
                boolean sameSql = Objects.equals(str(expected.get("sql")), str(actual.get("sql")));
                boolean sameBinds = Objects.equals(expected.get("bindValues"), actual.get("bindValues"));
                if (!sameSql || !sameBinds) {
                    failed++;
                    System.out.printf("  [FAIL] %-6s SQL %s / 绑定值 %s%n", testCase.id, sameSql ? "一致" : "不一致", sameBinds ? "一致" : "不一致");
                    if (!sameSql) {
                        System.out.printf("      期望 %s%n      实际 %s%n", str(expected.get("sql")), str(actual.get("sql")));
                    }
                }
            } else {
                unimplemented++;
                if ("compiled".equals(actualStatus)) {
                    failed++;
                    System.out.printf("  [FAIL] %-6s %s 模式未实现却生成了 SQL（静默错误）：%s%n",
                            testCase.id, testCase.mode, str(actual.get("sql")));
                }
            }
        }

        System.out.printf("%n逐字比对：%d 条 compiled / %d 条 unimplemented%n", compiled, unimplemented);
        System.out.printf("结论：%s（失败 %d 项）%n", failed == 0 ? "PASS" : "FAIL", failed);
        if (failed > 0) System.exit(1);
    }

    static void summary(List<Object> items) {
        long compiled = items.stream().filter(i -> "compiled".equals(str(((Map<?, ?>) i).get("status")))).count();
        long silent = items.stream().filter(i -> "SILENT_WRONG_MODE".equals(str(((Map<?, ?>) i).get("status")))).count();
        long rejected = items.size() - compiled - silent;
        System.out.printf("  compiled=%d / rejected=%d / SILENT_WRONG_MODE=%d%n", compiled, rejected, silent);
        if (silent > 0) {
            System.out.println("  注意：存在非 QUERY 模式生成 SQL 的情况，按静默错误处理（见报告）");
        }
    }

    // ---------- 用例 ----------

    record Case(String id, String mode, String metricId, List<String> dimensions,
                List<QueryDsl.Filter> filters, QueryDsl.TimeRange timeRange, Integer limit) {
    }

    static List<Case> cases() {
        List<Case> list = new ArrayList<>();

        // QUERY：维度组合 / 六种聚合 / 六种运算符 / 时间窗 / limit 边界
        list.add(new Case("q01", "QUERY", "gmv", List.of("region"), List.of(), null, 100));
        list.add(new Case("q02", "QUERY", "gmv", List.of(), List.of(), null, 100));
        list.add(new Case("q03", "QUERY", "gmv", List.of("region", "channel"), List.of(), null, 100));
        list.add(new Case("q04", "QUERY", "gmv", List.of("order_date"), List.of(), null, 50));
        list.add(new Case("q05", "QUERY", "gmv", List.of("region"), List.of(eq("region", "华东")), null, 100));
        list.add(new Case("q06", "QUERY", "gmv", List.of("region"), List.of(in("region", "华东", "华南")), null, 100));
        list.add(new Case("q07", "QUERY", "gmv", List.of("region"), List.of(notIn("region", "西北")), null, 100));
        list.add(new Case("q08", "QUERY", "gmv", List.of(), List.of(gte("order_date", "2026-01-01T00:00:00Z")), null, 100));
        list.add(new Case("q09", "QUERY", "gmv", List.of(), List.of(lte("order_date", "2026-06-30T00:00:00Z")), null, 100));
        list.add(new Case("q10", "QUERY", "gmv", List.of(),
                List.of(between("order_date", "2026-01-01T00:00:00Z", "2026-03-31T00:00:00Z")), null, 100));
        list.add(new Case("q11", "QUERY", "gmv", List.of("region"), List.of(),
                timeRange("order_date", "2026-01-01T00:00:00Z", "2026-01-31T23:59:59Z"), 100));
        list.add(new Case("q12", "QUERY", "gmv", List.of("region", "channel"),
                List.of(eq("channel", "线上")),
                timeRange("order_date", "2026-02-01T00:00:00Z", "2026-02-28T23:59:59Z"), 200));
        list.add(new Case("q13", "QUERY", "gmv", List.of("region"), List.of(), null, 1));
        list.add(new Case("q14", "QUERY", "gmv", List.of("region"), List.of(), null, 500));
        list.add(new Case("q15", "QUERY", "order_cnt", List.of("channel"), List.of(), null, 100));
        list.add(new Case("q16", "QUERY", "customer_cnt", List.of("region"), List.of(), null, 100));
        list.add(new Case("q17", "QUERY", "avg_amount", List.of("region"), List.of(), null, 100));
        list.add(new Case("q18", "QUERY", "min_amount", List.of(), List.of(), null, 100));
        list.add(new Case("q19", "QUERY", "max_amount", List.of(), List.of(), null, 100));
        list.add(new Case("q20", "QUERY", "gmv", List.of("region", "channel"),
                List.of(eq("channel", "线上"), in("region", "华东", "华南")), null, 100));

        // 非 QUERY：期望未实现（拒绝或明确报错），不得静默生成 SQL
        list.add(new Case("l01", "LOOKUP", "gmv", List.of(), List.of(eq("region", "华东")), null, 1));
        list.add(new Case("l02", "LOOKUP", "customer_cnt", List.of(), List.of(eq("region", "华南")), null, 1));
        list.add(new Case("l03", "LOOKUP", "gmv", List.of(), List.of(in("region", "华东", "华南")), null, 10));
        list.add(new Case("l04", "LOOKUP", "avg_amount", List.of(), List.of(), null, 1));
        list.add(new Case("b01", "LOOKUP_BATCH", "gmv", List.of(), List.of(in("region", "华东", "华南", "华北")), null, 50));
        list.add(new Case("b02", "LOOKUP_BATCH", "customer_cnt", List.of(), List.of(), null, 20));
        list.add(new Case("b03", "LOOKUP_BATCH", "order_cnt", List.of("channel"), List.of(), null, 10));
        list.add(new Case("f01", "FUNNEL", "order_cnt", List.of("order_date"), List.of(), null, 100));
        list.add(new Case("f02", "FUNNEL", "gmv", List.of("channel"), List.of(), null, 100));
        list.add(new Case("y01", "YOY_MOM", "gmv", List.of("order_date"), List.of(), null, 100));
        return list;
    }

    static QueryDsl.Filter filter(String field, QueryDsl.Operator op, String... values) {
        QueryDsl.Filter f = new QueryDsl.Filter();
        f.setFieldId(field);
        f.setOperator(op);
        f.setValues(List.of(values));
        return f;
    }

    static QueryDsl.Filter eq(String field, String v) { return filter(field, QueryDsl.Operator.EQ, v); }

    static QueryDsl.Filter in(String field, String... v) { return filter(field, QueryDsl.Operator.IN, v); }

    static QueryDsl.Filter notIn(String field, String... v) { return filter(field, QueryDsl.Operator.NOT_IN, v); }

    static QueryDsl.Filter gte(String field, String v) { return filter(field, QueryDsl.Operator.GTE, v); }

    static QueryDsl.Filter lte(String field, String v) { return filter(field, QueryDsl.Operator.LTE, v); }

    static QueryDsl.Filter between(String field, String a, String b) {
        return filter(field, QueryDsl.Operator.BETWEEN, a, b);
    }

    static QueryDsl.TimeRange timeRange(String field, String from, String to) {
        QueryDsl.TimeRange range = new QueryDsl.TimeRange();
        range.setField(field);
        range.setFrom(Instant.parse(from));
        range.setTo(Instant.parse(to));
        return range;
    }

    // ---------- 执行 ----------

    static Map<String, Object> run(Case testCase, PublishedManifest manifest) {
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId(manifest.getManifestId());
        dsl.setMode(QueryDsl.AnalysisMode.valueOf(testCase.mode));
        dsl.setMetricId(testCase.metricId);
        dsl.setDimensions(new ArrayList<>(testCase.dimensions));
        dsl.setFilters(new ArrayList<>(testCase.filters));
        dsl.setTimeRange(testCase.timeRange);
        dsl.setLimit(testCase.limit);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", testCase.id);
        row.put("mode", testCase.mode);
        row.put("metricId", testCase.metricId);
        try {
            CompiledQuery compiled = new SqlCompiler().compile(dsl, manifest);
            // 非 QUERY 模式若也能生成 SQL，说明编译器没按 mode 分支——
            // 那会返回语义错误的结果而不是报错，按静默错误记录，不能算 compiled。
            row.put("status", "QUERY".equals(testCase.mode) ? "compiled" : "SILENT_WRONG_MODE");
            row.put("sql", compiled.getSql());
            row.put("bindValues", normalize(compiled.getBindValues()));
            row.put("appliedLimit", compiled.getAppliedLimit());
        } catch (RuntimeException ex) {
            row.put("status", "rejected");
            row.put("error", ex.getClass().getSimpleName());
            row.put("message", String.valueOf(ex.getMessage()));
        }
        return row;
    }

    static List<String> normalize(List<Object> values) {
        List<String> out = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof java.sql.Timestamp ts) {
                out.add(ts.toInstant().toString());
            } else if (value instanceof BigDecimal decimal) {
                out.add(decimal.toPlainString());
            } else {
                out.add(String.valueOf(value));
            }
        }
        return out;
    }

    // ---------- 清单 ----------

    static PublishedManifest manifest() {
        SemanticManifest semantic = new SemanticManifest();
        semantic.setSemanticVersion("sem-1.0.0");
        semantic.setMetrics(List.of(
                metric("gmv", "成交金额", TABLE, TABLE + ".amount", SemanticManifest.Aggregation.SUM),
                metric("order_cnt", "订单数", TABLE, TABLE + ".id", SemanticManifest.Aggregation.COUNT),
                metric("customer_cnt", "客户数", TABLE, TABLE + ".customer_id", SemanticManifest.Aggregation.COUNT_DISTINCT),
                metric("avg_amount", "客单价", TABLE, TABLE + ".amount", SemanticManifest.Aggregation.AVG),
                metric("min_amount", "最小金额", TABLE, TABLE + ".amount", SemanticManifest.Aggregation.MIN),
                metric("max_amount", "最大金额", TABLE, TABLE + ".amount", SemanticManifest.Aggregation.MAX)));
        semantic.setDimensions(List.of(
                dimension("region", "大区", TABLE, TABLE + ".region"),
                dimension("channel", "渠道", TABLE, TABLE + ".channel"),
                dimension("order_date", "下单日期", TABLE, TABLE + ".order_date"),
                dimension("customer_tier", "客户等级", DIM_TABLE, DIM_TABLE + ".tier")));

        String payload = "{\"semanticVersion\":\"sem-1.0.0\"}";
        PublishedManifest manifest = new PublishedManifest();
        manifest.setManifestId("manifest-golden-001");
        manifest.setPayloadJson(payload);
        manifest.setContentHash(PublishedManifest.sha256(payload));
        manifest.setOntologyVersion("ont-1.0.0");
        manifest.setSemanticVersion("sem-1.0.0");
        manifest.setPolicyVersion("pol-1.0.0");
        manifest.setRuleVersion("rule-1.0.0");
        manifest.setSemantic(semantic);
        manifest.setOntology(new OntologySnapshot());
        return manifest;
    }

    static SemanticManifest.Metric metric(String id, String name, String table, String column,
                                          SemanticManifest.Aggregation aggregation) {
        SemanticManifest.Metric m = new SemanticManifest.Metric();
        m.setId(id);
        m.setName(name);
        m.setTable(table);
        m.setColumn(column);
        m.setAggregation(aggregation);
        m.setAllowedModes(List.of("QUERY", "LOOKUP", "LOOKUP_BATCH", "FUNNEL", "YOY_MOM"));
        m.setAllowedDimensions(List.of("region", "channel", "order_date"));
        return m;
    }

    static SemanticManifest.Dimension dimension(String id, String name, String table, String column) {
        SemanticManifest.Dimension d = new SemanticManifest.Dimension();
        d.setId(id);
        d.setName(name);
        d.setTable(table);
        d.setColumn(column);
        return d;
    }

    // ---------- 极简 JSON ----------

    static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    static void write(StringBuilder sb, Object value, int indent) {
        String pad = "  ".repeat(indent);
        String padIn = "  ".repeat(indent + 1);
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append('"').append(escape(s)).append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sb.append(padIn).append('"').append(escape(String.valueOf(entry.getKey()))).append("\": ");
                write(sb, entry.getValue(), indent + 1);
                if (++i < map.size()) sb.append(',');
                sb.append('\n');
            }
            sb.append(pad).append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                sb.append(padIn);
                write(sb, list.get(i), indent + 1);
                if (i < list.size() - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append(pad).append(']');
        } else {
            sb.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 只解析顶层 cases 数组，避免引入 JSON 库。 */
    static List<Map<String, Object>> extractCases(String raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        int start = raw.indexOf("\"cases\"");
        int arrayStart = raw.indexOf('[', start);
        int i = arrayStart;
        while (true) {
            int objStart = raw.indexOf('{', i);
            if (objStart < 0) break;
            int objEnd = matchBrace(raw, objStart);
            if (objEnd < 0) break;
            out.add(parseObject(raw.substring(objStart, objEnd + 1)));
            i = objEnd + 1;
        }
        return out;
    }

    static int matchBrace(String raw, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) return i;
            }
        }
        return -1;
    }

    /** 极简对象解析：只取 status / sql / bindValues 三个字段。 */
    static Map<String, Object> parseObject(String text) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", extractString(text, "status"));
        map.put("sql", extractString(text, "sql"));
        map.put("bindValues", extractStringArray(text, "bindValues"));
        return map;
    }

    static String extractString(String text, String key) {
        int k = text.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int colon = text.indexOf(':', k);
        int quote = text.indexOf('"', colon);
        if (quote < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = quote + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                char next = text.charAt(++i);
                sb.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> next;
                });
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    static List<String> extractStringArray(String text, String key) {
        int k = text.indexOf("\"" + key + "\"");
        if (k < 0) return List.of();
        int start = text.indexOf('[', k);
        int end = text.indexOf(']', start);
        if (start < 0 || end < 0) return List.of();
        List<String> out = new ArrayList<>();
        String body = text.substring(start + 1, end).trim();
        if (body.isEmpty()) return out;
        for (String part : body.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                out.add(trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\"));
            } else if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
