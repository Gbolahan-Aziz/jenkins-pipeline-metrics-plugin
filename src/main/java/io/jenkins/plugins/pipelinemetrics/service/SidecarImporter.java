package io.jenkins.plugins.pipelinemetrics.service;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import io.jenkins.plugins.pipelinemetrics.model.BuildRecord;
import io.jenkins.plugins.pipelinemetrics.model.StageRecord;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** Imports build/stage rows from an existing pipeline-metrics sidecar SQLite file. */
@Extension
public class SidecarImporter {

    private static final Logger LOGGER = Logger.getLogger(SidecarImporter.class.getName());

    public static SidecarImporter get() {
        return Jenkins.get().getExtensionList(SidecarImporter.class).get(0);
    }

    public JSONObject importFrom(@NonNull String path) {
        JSONObject out = new JSONObject();
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) {
            out.put("status", "error");
            out.put("message", "File does not exist or is not readable: " + path);
            return out;
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + f.getAbsolutePath());

        MetricsStore store = MetricsStore.get();

        try (Connection src = ds.getConnection()) {
            if (!hasBuildsTable(src)) {
                out.put("status", "error");
                out.put("message", "Not a valid pipeline-metrics database (missing 'builds' table)");
                return out;
            }
            try (Statement st = src.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT id, job_full_name, job_folder, job_name, build_number, result, "
                            + "duration_ms, queue_time_ms, timestamp_ms, built_on, node_labels, triggered_by "
                            + "FROM builds")) {
                RowCopier.Counts counts = RowCopier.copy(rs, (buildId, b) -> loadStages(src, buildId, b), store);
                out.put("status", "ok");
                out.put("read", counts.read);
                out.put("inserted", counts.inserted);
                out.put("updated", counts.updated);
                out.put("skipped", counts.skipped);
            }
        } catch (SQLException e) {
            out.put("status", "error");
            out.put("message", "Could not read SQLite database: " + e.getMessage());
        }
        return out;
    }

    private static void loadStages(Connection src, long sidecarBuildId, BuildRecord b) throws SQLException {
        try (PreparedStatement ps = src.prepareStatement(
                "SELECT stage_name, status, duration_ms FROM stages WHERE build_id=? ORDER BY id")) {
            ps.setLong(1, sidecarBuildId);
            try (ResultSet rs = ps.executeQuery()) {
                int seq = 0;
                while (rs.next()) {
                    b.getStages().add(new StageRecord(
                            RowCopier.nz(rs.getString("stage_name")), rs.getString("status"),
                            rs.getLong("duration_ms"), seq++));
                }
            }
        } catch (SQLException e) {
            // sidecar without a stages table: import the build without stages
            LOGGER.log(Level.FINE, "No stage data for sidecar build " + sidecarBuildId, e);
        }
    }

    private static boolean hasBuildsTable(Connection src) throws SQLException {
        try (Statement st = src.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='builds'")) {
            return rs.next();
        }
    }
}
