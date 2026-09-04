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
import net.sf.json.JSONObject;

/**
 * Copies all build/stage history from one {@link MetricsStore} to another. Used when an admin
 * switches the configured storage backend on an existing install and wants to bring history
 * along — the same row-copy shape as {@link SidecarImporter}, generalized (via {@link RowCopier})
 * to two live plugin-schema stores instead of one sidecar file and one plugin store. Needs no
 * dialect-specific SQL: a plain {@code SELECT} is portable, and {@code target.upsertBuild()}
 * already goes through the target's own {@code SqlDialect}.
 */
public final class StorageMigrator {

    private StorageMigrator() {
    }

    /** Copies every build (and its stages) from {@code source} into {@code target}, upserting. */
    @NonNull
    public static JSONObject migrate(@NonNull MetricsStore source, @NonNull MetricsStore target) {
        JSONObject out = new JSONObject();
        try {
            RowCopier.Counts counts = source.query(conn -> {
                try (Statement st = conn.createStatement();
                        ResultSet rs = st.executeQuery(
                                "SELECT id, job_full_name, job_folder, job_name, build_number, result, "
                                + "duration_ms, queue_time_ms, timestamp_ms, built_on, node_labels, triggered_by "
                                + "FROM builds")) {
                    return RowCopier.copy(rs, (buildId, b) -> loadStages(conn, buildId, b), target);
                }
            });
            out.put("status", "ok");
            out.put("read", counts.read);
            out.put("inserted", counts.inserted);
            out.put("updated", counts.updated);
            out.put("skipped", counts.skipped);
        } catch (SQLException e) {
            out.put("status", "error");
            out.put("message", "Could not read source store: " + e.getMessage());
        }
        return out;
    }

    private static void loadStages(Connection conn, long buildId, BuildRecord b) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT stage_name, status, duration_ms, seq FROM stages WHERE build_id=? ORDER BY seq")) {
            ps.setLong(1, buildId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    b.getStages().add(new StageRecord(
                            RowCopier.nz(rs.getString("stage_name")), rs.getString("status"),
                            rs.getLong("duration_ms"), rs.getInt("seq")));
                }
            }
        }
    }
}
