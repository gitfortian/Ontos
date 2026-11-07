import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PoC #9「运维自检与可观测性」最小探针（M0 / W3 / T3.3，判据级交付）。
 *
 * 判据（技术选型 v1.1 §7 #9）：5 类异常注入各 ≥10 次，fail-closed 拦截率 100%；
 * 3 副本看板一致、新清单全副本可见延迟 ≤30 秒、框架 OTel 与 Studio 观测。
 *
 * M0 边界：产品侧启动自检属 M4，多副本与 pgbouncer 环境本地不具备，
 * 因此本探针只验证「本地单副本可做前两项」的注入条件读数（总览 §3.3），
 * 完整验收待 M4 / 部署环境回填：
 *   (1) pool_mode != transaction   → 本地无 pgbouncer，验证「不在链路」可探测；
 *   (2) server_reset_query 缺失    → 依赖 pgbouncer，同上 env_pending；
 *   (3) 当前角色为 owner / BYPASSRLS → 本地实测（只读角色必须非 owner、非 BYPASSRLS）；
 *   (4) forcerowsecurity = false   → 本地实测（目标表必须 FORCE RLS）；
 *   (5) 副本 content_hash 不一致   → 单副本退化为「清单哈希可从 System DB 读出」，多副本待部署环境。
 * 运行：java -cp <postgresql-driver.jar> scripts/poc/Poc09SelfCheck.java
 * 产物：docs/06-verification/poc/PoC-09-probe-raw.json（stdout JSON，重定向落盘）
 */
public class Poc09SelfCheck {

    private static final String QUERY_DS_URL = "jdbc:postgresql://127.0.0.1:5432/ontos_queryds";
    private static final String SYSTEM_DB_URL = "jdbc:postgresql://127.0.0.1:5432/ontos";
    private static final String READONLY_USER = "ontos_query_ro";
    private static final String READONLY_PASSWORD = "ontos_query_ro";
    private static final String ADMIN_USER = "postgres";
    private static final String ADMIN_PASSWORD = "123456";
    private static final String TARGET_TABLE = "sales_order";

    public static void main(String[] args) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("probe", "PoC-09 最小探针（判据级交付）");
        result.put("criteriaRef", "技术选型 v1.1 §7 #9");
        result.put("setStatus", "pending_m4 —— 本地可跑项已实测；pool 注入、多副本一致性、OTel/Studio 待 M4 与部署环境回填");

        // (3) 只读角色属性：非 superuser、非 BYPASSRLS、非目标表 owner
        try (Connection c = DriverManager.getConnection(QUERY_DS_URL, READONLY_USER, READONLY_PASSWORD)) {
            c.setReadOnly(true);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                boolean ok = !rs.getBoolean(1) && !rs.getBoolean(2);
                Map<String, Object> check = new LinkedHashMap<>();
                check.put("expect", "非 superuser 且非 BYPASSRLS");
                check.put("rolsuper", rs.getBoolean(1));
                check.put("rolbypassrls", rs.getBoolean(2));
                check.put("status", ok ? "pass" : "violation_detected");
                result.put("check3_role_attributes", check);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = ?");
                 PreparedStatement ps2 = c.prepareStatement(
                    "SELECT relforcerowsecurity FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relname = ?")) {
                ps.setString(1, TARGET_TABLE);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    String owner = rs.getString(1);
                    boolean ok = !READONLY_USER.equals(owner);
                    Map<String, Object> check = new LinkedHashMap<>();
                    check.put("expect", "只读角色不是表 owner");
                    check.put("tableowner", owner);
                    check.put("status", ok ? "pass" : "violation_detected");
                    result.put("check3_table_owner", check);
                }
                // (4) FORCE RLS
                ps2.setString(1, TARGET_TABLE);
                try (ResultSet rs = ps2.executeQuery()) {
                    rs.next();
                    boolean forced = rs.getBoolean(1);
                    Map<String, Object> check = new LinkedHashMap<>();
                    check.put("expect", "relforcerowsecurity = true");
                    check.put("relforcerowsecurity", forced);
                    check.put("status", forced ? "pass" : "violation_detected");
                    result.put("check4_force_rls", check);
                }
            }
            // (1) pgbouncer pool_mode：直连 PG 时 SHOW POOL_MODE 不可用，即「pgbouncer 不在链路」的探针证据
            Map<String, Object> check1 = new LinkedHashMap<>();
            check1.put("expect", "pool_mode = transaction（pgbouncer 链路）");
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW POOL_MODE")) {
                rs.next();
                String mode = rs.getString(1);
                check1.put("poolMode", mode);
                check1.put("status", "transaction".equals(mode) ? "pass" : "violation_detected");
            } catch (Exception e) {
                check1.put("status", "env_pending");
                check1.put("evidence", "SHOW POOL_MODE 不可用：" + e.getMessage().split("\n")[0]);
            }
            result.put("check1_pool_mode", check1);
            // (2) server_reset_query：只能经 pgbouncer 管理口查询，本地 env_pending
            Map<String, Object> check2 = new LinkedHashMap<>();
            check2.put("expect", "server_reset_query = DISCARD ALL（pgbouncer 配置）");
            check2.put("status", "env_pending");
            check2.put("evidence", "依赖 pgbouncer 管理库，本地无该组件；M4 / 部署环境补验");
            result.put("check2_server_reset_query", check2);
        }

        // (5) 单副本退化：System DB 的发布清单哈希可读（多副本一致性待部署环境）
        try (Connection c = DriverManager.getConnection(SYSTEM_DB_URL, ADMIN_USER, ADMIN_PASSWORD)) {
            Map<String, Object> check5 = new LinkedHashMap<>();
            check5.put("expect", "多副本 content_hash 一致（偏差 ≤1 个健康检查周期）");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                    "SELECT manifest_id, content_hash FROM published_snapshot ORDER BY created_at DESC LIMIT 1")) {
                if (rs.next()) {
                    check5.put("manifestId", rs.getString(1));
                    check5.put("contentHash", rs.getString(2));
                    check5.put("status", "single_replica_info");
                    check5.put("note", "单副本仅验证哈希可读且应用加载值可比对；跨副本一致性待部署环境");
                } else {
                    check5.put("status", "no_manifest");
                }
            }
            result.put("check5_manifest_hash", check5);
        }

        System.out.println(toJson(result));
    }

    /** 极简 JSON 序列化：本探针的值只有 Map / String / Boolean，不引第三方依赖。 */
    private static String toJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(entry.getKey()).append("\":").append(toJson(entry.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return String.valueOf(value);
    }
}
