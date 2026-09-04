package io.jenkins.plugins.pipelinemetrics.query;

import io.jenkins.plugins.pipelinemetrics.query.FilterSet.Where;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import io.jenkins.plugins.pipelinemetrics.store.dialect.SqlDialect;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * Runs the analytics SQL against a {@link MetricsStore} and returns JSON. The no-arg constructor
 * (used by the long-lived {@code MetricsApi} instance) re-resolves {@link MetricsStore#get()} on
 * every call rather than capturing a snapshot, so switching the configured storage backend takes
 * effect for the dashboard immediately — the same way it already did for collection/retention —
 * instead of leaving the API bound to a closed pool until Jenkins restarts.
 */
public class MetricsQueryService {

    private static final List<String> GROUPINGS = Arrays.asList("hour", "day", "week");

    private final Supplier<MetricsStore> storeSupplier;

    /** Binds permanently to this exact store instance — for tests and any other explicit use. */
    public MetricsQueryService(MetricsStore store) {
        this.storeSupplier = () -> store;
    }

    /** Always resolves the current {@link MetricsStore#get()} singleton at call time. */
    public MetricsQueryService() {
        this.storeSupplier = MetricsStore::get;
    }

    private MetricsStore store() {
        return storeSupplier.get();
    }

    private SqlDialect dialect() {
        return store().dialect();
    }

    public JSONObject filters() throws SQLException {
        return store().query(conn -> {
            JSONObject out = new JSONObject();
            out.put("folders", distinct(conn, "SELECT DISTINCT job_folder FROM builds ORDER BY job_folder"));
            out.put("agents", distinct(conn, "SELECT DISTINCT built_on FROM builds ORDER BY built_on"));
            out.put("jobs", distinct(conn, "SELECT DISTINCT job_full_name FROM builds ORDER BY job_full_name"));
            out.put("users", distinct(conn,
                    "SELECT DISTINCT triggered_by FROM builds WHERE triggered_by != '' ORDER BY triggered_by"));
            return out;
        });
    }

    public JSONObject overview(FilterSet f) throws SQLException {
        String sql = "SELECT COUNT(*) total_builds,"
                + " SUM(CASE WHEN result='SUCCESS' THEN 1 ELSE 0 END) successful,"
                + " SUM(CASE WHEN result='FAILURE' THEN 1 ELSE 0 END) failed,"
                + " SUM(CASE WHEN result='UNSTABLE' THEN 1 ELSE 0 END) unstable,"
                + " SUM(CASE WHEN result='ABORTED' THEN 1 ELSE 0 END) aborted,"
                + " AVG(duration_ms) avg_duration_ms, AVG(queue_time_ms) avg_queue_time_ms,"
                + " MAX(duration_ms) max_duration_ms FROM builds ";
        return store().query(conn -> {
            Where w = f.where();
            JSONObject cur = row(conn, sql + w.sql, w.params);
            Where pw = f.where(f.days);
            JSONObject prev = row(conn, sql + pw.sql, pw.params);
            cur.put("prev_total_builds", prev.optInt("total_builds", 0));
            cur.put("prev_successful", prev.optInt("successful", 0));
            cur.put("prev_failed", prev.optInt("failed", 0));
            cur.put("prev_avg_duration_ms", prev.optDouble("avg_duration_ms", 0));
            return cur;
        });
    }

    public JSONArray trends(FilterSet f, String groupBy) throws SQLException {
        if (!GROUPINGS.contains(groupBy)) {
            throw new IllegalArgumentException("Invalid group_by: " + groupBy);
        }
        Where w = f.where();
        String sql = "SELECT " + dialect().periodBucketExpr(groupBy) + " period,"
                + " COUNT(*) total,"
                + " SUM(CASE WHEN result='SUCCESS' THEN 1 ELSE 0 END) success,"
                + " SUM(CASE WHEN result='FAILURE' THEN 1 ELSE 0 END) failures,"
                + " AVG(duration_ms) avg_duration_ms, AVG(queue_time_ms) avg_queue_ms"
                + " FROM builds " + w.sql + " GROUP BY period ORDER BY period";
        return rows(sql, w.params);
    }

    public JSONArray pipelines(FilterSet f, String sort, int limit) throws SQLException {
        String order;
        switch (sort == null ? "" : sort) {
            case "failure_rate": order = "failure_rate DESC"; break;
            case "total_builds": order = "total_builds DESC"; break;
            case "max_duration": order = "max_duration_ms DESC"; break;
            case "avg_queue": order = "avg_queue_ms DESC"; break;
            case "avg_duration": order = "avg_duration_ms DESC"; break;
            default: order = "avg_duration_ms DESC"; break;
        }
        Where w = f.where();
        String sql = "SELECT job_full_name, job_folder, job_name, COUNT(*) total_builds,"
                + " AVG(duration_ms) avg_duration_ms, MAX(duration_ms) max_duration_ms,"
                + " AVG(queue_time_ms) avg_queue_ms,"
                + " ROUND(100.0*SUM(CASE WHEN result='FAILURE' THEN 1 ELSE 0 END)/COUNT(*),1) failure_rate,"
                + " SUM(CASE WHEN result='SUCCESS' THEN 1 ELSE 0 END) success_count,"
                + " SUM(CASE WHEN result='FAILURE' THEN 1 ELSE 0 END) failure_count"
                + " FROM builds " + w.sql
                + " GROUP BY job_full_name ORDER BY " + order + ", job_full_name ASC LIMIT ?";
        List<Object> params = new ArrayList<>(w.params);
        params.add(limit);
        return rows(sql, params);
    }

    public JSONArray agents(FilterSet f) throws SQLException {
        Where w = f.where();
        String sql = "SELECT CASE WHEN built_on='' THEN 'built-in' ELSE built_on END agent,"
                + " node_labels, COUNT(*) total_builds, AVG(duration_ms) avg_duration_ms,"
                + " ROUND(100.0*SUM(CASE WHEN result='FAILURE' THEN 1 ELSE 0 END)/COUNT(*),1) failure_rate,"
                + " SUM(duration_ms) total_busy_ms FROM builds " + w.sql
                + " GROUP BY built_on ORDER BY total_builds DESC";
        return rows(sql, w.params);
    }

    public JSONArray stages(String job, int days, String folder, String agent, String user) throws SQLException {
        // Reuses FilterSet's folder/agent/user WHERE-building (including the built-in/master
        // agent-alias rule) instead of re-deriving it here, so that logic has exactly one home.
        FilterSet f = new FilterSet(days, folder, agent, user);
        FilterSet.Where w = f.where(0, "b");
        String whereSql = w.sql;
        List<Object> params = new ArrayList<>(w.params);
        if (job != null && !job.isEmpty()) {
            whereSql += " AND b.job_full_name = ?";
            params.add(job);
        }
        String sql = "SELECT s.stage_name, COUNT(*) run_count, AVG(s.duration_ms) avg_duration_ms,"
                + " MAX(s.duration_ms) max_duration_ms,"
                + " ROUND(SUM(CASE WHEN s.status IN ('FAILED','ERROR') THEN 1 ELSE 0 END)*1.0/COUNT(*),3) failure_rate"
                + " FROM stages s JOIN builds b ON s.build_id=b.id " + whereSql
                + " GROUP BY s.stage_name ORDER BY avg_duration_ms DESC";
        return rows(sql, params);
    }

    public JSONArray heatmap(FilterSet f) throws SQLException {
        Where w = f.where();
        // "AS" before each alias is required, not stylistic: Postgres's grammar treats HOUR as
        // special in EXTRACT/INTERVAL contexts, and a bare "... ) hour," alias hits that
        // ambiguity as a genuine syntax error rather than being parsed as a plain column label.
        String sql = "SELECT " + dialect().dayOfWeekExpr() + " AS day_of_week,"
                + " " + dialect().hourOfDayExpr() + " AS hour,"
                + " COUNT(*) AS count, SUM(CASE WHEN result='FAILURE' THEN 1 ELSE 0 END) AS failures"
                + " FROM builds " + w.sql + " GROUP BY day_of_week, hour ORDER BY day_of_week, hour";
        return rows(sql, w.params);
    }

    public JSONArray users(FilterSet f) throws SQLException {
        Where w = f.where();
        String sql = "SELECT CASE WHEN triggered_by='' THEN 'unknown' ELSE triggered_by END user,"
                + " COUNT(*) total_builds,"
                + " SUM(CASE WHEN result='SUCCESS' THEN 1 ELSE 0 END) success_count,"
                + " SUM(CASE WHEN result='FAILURE' THEN 1 ELSE 0 END) failure_count,"
                + " ROUND(100.0*SUM(CASE WHEN result='FAILURE' THEN 1 ELSE 0 END)/COUNT(*),1) failure_rate,"
                + " AVG(duration_ms) avg_duration_ms FROM builds " + w.sql
                + " AND triggered_by LIKE 'user:%'"
                + " GROUP BY triggered_by ORDER BY total_builds DESC";
        return rows(sql, w.params);
    }

    private JSONArray rows(String sql, List<Object> params) throws SQLException {
        return store().query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return toArray(rs);
                }
            }
        });
    }

    private static JSONObject row(java.sql.Connection conn, String sql, List<Object> params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                JSONArray arr = toArray(rs);
                return arr.isEmpty() ? new JSONObject() : arr.getJSONObject(0);
            }
        }
    }

    private static JSONArray distinct(java.sql.Connection conn, String sql) throws SQLException {
        JSONArray out = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    private static JSONArray toArray(ResultSet rs) throws SQLException {
        JSONArray arr = new JSONArray();
        int cols = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            JSONObject o = new JSONObject();
            for (int i = 1; i <= cols; i++) {
                o.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
            }
            arr.add(o);
        }
        return arr;
    }
}
