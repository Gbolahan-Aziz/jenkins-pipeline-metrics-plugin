package io.jenkins.plugins.pipelinemetrics.service;

import io.jenkins.plugins.pipelinemetrics.model.BuildRecord;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore.UpsertOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared "read builds rows from a source, load each one's stages, upsert into a target
 * {@link MetricsStore}, tally counts" logic used by both {@link SidecarImporter} (source: an
 * arbitrary external sidecar SQLite file) and {@link StorageMigrator} (source: another live
 * plugin-schema store). Both sources expose the same builds/stages column layout, so one
 * implementation covers both rather than two independently-maintained copies.
 */
final class RowCopier {

    private static final Logger LOGGER = Logger.getLogger(RowCopier.class.getName());

    static final class Counts {
        int read;
        int inserted;
        int updated;
        int skipped;
    }

    @FunctionalInterface
    interface StagesLoader {
        void load(long sourceBuildId, BuildRecord target) throws SQLException;
    }

    private RowCopier() {
    }

    /**
     * Iterates {@code buildsResultSet} (must expose the standard builds columns: id,
     * job_full_name, job_folder, job_name, build_number, result, duration_ms, queue_time_ms,
     * timestamp_ms, built_on, node_labels, triggered_by), copying each row — with its stages,
     * loaded via {@code stagesLoader} — into {@code target}.
     */
    static Counts copy(ResultSet buildsResultSet, StagesLoader stagesLoader, MetricsStore target) throws SQLException {
        Counts counts = new Counts();
        while (buildsResultSet.next()) {
            counts.read++;
            try {
                BuildRecord b = new BuildRecord();
                b.setJobFullName(buildsResultSet.getString("job_full_name"));
                b.setJobFolder(nz(buildsResultSet.getString("job_folder")));
                b.setJobName(nz(buildsResultSet.getString("job_name")));
                b.setBuildNumber(buildsResultSet.getInt("build_number"));
                b.setResult(buildsResultSet.getString("result"));
                b.setDurationMs(buildsResultSet.getLong("duration_ms"));
                b.setQueueTimeMs(buildsResultSet.getLong("queue_time_ms"));
                b.setTimestampMs(buildsResultSet.getLong("timestamp_ms"));
                b.setBuiltOn(nz(buildsResultSet.getString("built_on")));
                b.setNodeLabels(nz(buildsResultSet.getString("node_labels")));
                b.setTriggeredBy(nz(buildsResultSet.getString("triggered_by")));
                if (b.getJobFullName() == null || b.getJobFullName().isEmpty()) {
                    counts.skipped++;
                    continue;
                }
                stagesLoader.load(buildsResultSet.getLong("id"), b);
                UpsertOutcome outcome = target.upsertBuild(b);
                switch (outcome) {
                    case INSERTED:
                        counts.inserted++;
                        break;
                    case UPDATED:
                        counts.updated++;
                        break;
                    case FAILED:
                    default:
                        counts.skipped++;
                        break;
                }
            } catch (RuntimeException e) {
                counts.skipped++;
                LOGGER.log(Level.FINE, "Skipping row during copy", e);
            }
        }
        return counts;
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
