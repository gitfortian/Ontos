import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PoC #1 RLS 最终边界探针（M0 / W2 / T2.1）。
 *
 * <p>用应用同款 JDBC 路径连接 Query DS，覆盖五组用例，每组重复 100 次：
 * 跨租户、跨域、未授权租户存在性探测、未设 GUC、事务池连接复用。
 * 判据与阈值引用技术选型 v1.1 §7 #1；本探针只输出实测结果。
 *
 * <p>运行：java -cp <postgresql-driver.jar> scripts/poc/Poc01Rls.java
 */
public class Poc01Rls {

    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/ontos_queryds";
    private static final String USER = "ontos_query_ro";
    private static final String PASSWORD = "ontos_query_ro";
    private static final int REPEAT = 100;

    private static final String SET_GUC =
            "SELECT set_config('ontos.tenant_id', ?, true), set_config('ontos.user_id', ?, true)";
    private static final String COUNT_SQL = "SELECT count(*) FROM public.sales_order";

    public static void main(String[] args) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database", "ontos_queryds@127.0.0.1:5432");
        result.put("role", USER);
        result.put("repeatPerCase", REPEAT);

        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);

            result.put("forceRowLevelSecurity", forceRlsEnabled(connection));
            result.put("baselineRowsWithoutGuc", count(connection));

            int violations = 0;
            long started = System.currentTimeMillis();

            for (int i = 0; i < REPEAT; i++) {
                setTenant(connection, "demo", "demo-user");
                if (count(connection) != 2) violations++; // 跨租户：只能看到 demo 的 2 行
                connection.commit();

                setTenant(connection, "other", "demo-user");
                if (count(connection) != 1) violations++; // 跨域：只能看到 other 的 1 行
                connection.commit();

                setTenant(connection, "unauthorized", "demo-user");
                if (count(connection) != 0) violations++; // 存在性探测：未授权租户 0 行
                connection.commit();

                if (count(connection) != 0) violations++; // 未设 GUC：事务结束后 GUC 失效，仍 0 行
                connection.commit();

                setTenant(connection, "demo", "demo-user");
                int reused = count(connection); // 同一物理连接复用后不串号
                connection.commit();
                setTenant(connection, "other", "demo-user");
                int afterSwitch = count(connection);
                connection.commit();
                if (reused != 2 || afterSwitch != 1) violations++;
            }

            result.put("cases", 5);
            result.put("totalChecks", REPEAT * 5);
            result.put("violations", violations);
            result.put("elapsedMs", System.currentTimeMillis() - started);
            result.put("conclusion", violations == 0 ? "PASS" : "FAIL");
        }

        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : result.entrySet()) {
            if (!first) json.append(", ");
            first = false;
            json.append('"').append(entry.getKey()).append("\": ");
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) json.append(value);
            else json.append('"').append(value).append('"');
        }
        System.out.println(json.append('}'));
    }

    private static void setTenant(Connection connection, String tenantId, String userId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(SET_GUC)) {
            statement.setString(1, tenantId);
            statement.setString(2, userId);
            statement.execute();
        }
    }

    private static int count(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(COUNT_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static boolean forceRlsEnabled(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT relforcerowsecurity FROM pg_class WHERE oid = 'public.sales_order'::regclass");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getBoolean(1);
        }
    }

}
