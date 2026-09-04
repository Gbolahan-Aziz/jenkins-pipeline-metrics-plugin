package io.jenkins.plugins.pipelinemetrics.service;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.pipelinemetrics.model.BuildRecord;
import io.jenkins.plugins.pipelinemetrics.model.StageRecord;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.json.JSONObject;

/**
 * Copies all build/stage history from one {@link MetricsStore} to another. Used when an admin
 * switches the configured storage backend on an existing install and wants to bring history
 * along — the same row-copy shape as {@link SidecarImporter}, generalized to two live
 * plugin-schema stores instead of one sidecar file and one plugin store. Needs no
 * dialect-specific SQL: a plain {@code SELECT} is portable, and {@code target.upsertBuild()}
 * already goes through the target's own {@code SqlDialect}.
 */
public final class StorageMigrator {

    private static final Logger LOGGER = Logger.getLogger(StorageMigrator.class.getName());

    private StorageMigrator() {
    }

    /** Copies every build (and its stages) from {@code source} into {@code target}, upserting. */
    @NonNull
    public static JSONObject migrate(@NonNull MetricsStore source, @NonNull MetricsStore target) {
        JSONObject out = new JSONObject();
        int[] counts = new int[4]; // read, inserted, updated, skipped

        try {
            source.query(conn -> {
                try (Statement st = conn.createStatement();
                        ResultSet rs = st.executeQuery(
                                "SELECT id, job_full_name, job_folder, job_name, build_number, result, "
                                + "duration_ms, queue_time_ms, timestamp_ms, built_on, node_labels, triggered_by "
                                + "FROM builds")) {
                    while (rs.next()) {
                        counts[0]++;
                        try {
                            BuildRecord b = new BuildRecord();
                            b.setJobFullName(rs.getString("job_full_name"));
                            b.setJobFolder(nz(rs.getString("job_folder")));
                            b.setJobName(nz(rs.getString("job_name")));
                            b.setBuildNumber(rs.getInt("build_number"));
                            b.setResult(rs.getString("result"));
                            b.setDurationMs(rs.getLong("duration_ms"));
                            b.setQueueTimeMs(rs.getLong("queue_time_ms"));
                            b.setTimestampMs(rs.getLong("timestamp_ms"));
                            b.setBuiltOn(nz(rs.getString("built_on")));
                            b.setNodeLabels(nz(rs.getString("node_labels")));
                            b.setTriggeredBy(nz(rs.getString("triggered_by")));
                            if (b.getJobFullName() == null || b.getJobFullName().isEmpty()) {
                                counts[3]++;
                                continue;
                            }
                            loadStages(conn, rs.getLong("id"), b);
                            boolean exists = exists(target, b.getJobFullName(), b.getBuildNumber());
                            target.upsertBuild(b);
                            if (exists) {
                                counts[2]++;
                            } else {
                                counts[1]++;
                            }
                        } catch (RuntimeException e) {
                            counts[3]++;
                            LOGGER.log(Level.FINE, "Skipping row during storage migration", e);
                        }
                    }
                }
                return null;
            });
            out.put("status", "ok");
        } catch (SQLException e) {
            out.put("status", "error");
            out.put("message", "Could not read source store: " + e.getMessage());
        }
        out.put("read", counts[0]);
        out.put("inserted", counts[1]);
        out.put("updated", counts[2]);
        out.put("skipped", counts[3]);
        return out;
    }

    private static void loadStages(Connection conn, long buildId, BuildRecord b) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT stage_name, status, duration_ms, seq FROM stages WHERE build_id=? ORDER BY seq")) {
            ps.setLong(1, buildId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    b.getStages().add(new StageRecord(
                            nz(rs.getString("stage_name")), rs.getString("status"),
                            rs.getLong("duration_ms"), rs.getInt("seq")));
                }
            }
        }
    }

    private static boolean exists(MetricsStore store, String jobFullName, int buildNumber) {
        try {
            Boolean found = store.query(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM builds WHERE job_full_name=? AND build_number=?")) {
                    ps.setString(1, jobFullName);
                    ps.setInt(2, buildNumber);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next();
                    }
                }
            });
            return Boolean.TRUE.equals(found);
        } catch (SQLException e) {
            return false;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
