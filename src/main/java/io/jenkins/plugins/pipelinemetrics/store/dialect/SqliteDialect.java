package io.jenkins.plugins.pipelinemetrics.store.dialect;

import java.util.Arrays;
import java.util.List;

/** The embedded-SQLite dialect: the plugin's original, zero-configuration default backend. */
public final class SqliteDialect implements SqlDialect {

    @Override
    public String id() {
        return "sqlite";
    }

    @Override
    public List<String> initialSchemaStatements() {
        return Arrays.asList(
                "CREATE TABLE builds ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "job_full_name TEXT NOT NULL,"
                        + "job_folder TEXT NOT NULL,"
                        + "job_name TEXT NOT NULL,"
                        + "build_number INTEGER NOT NULL,"
                        + "result TEXT,"
                        + "duration_ms INTEGER,"
                        + "queue_time_ms INTEGER,"
                        + "timestamp_ms INTEGER NOT NULL,"
                        + "built_on TEXT DEFAULT '',"
                        + "node_labels TEXT DEFAULT '',"
                        + "triggered_by TEXT DEFAULT '',"
                        + "UNIQUE(job_full_name, build_number))",
                "CREATE TABLE stages ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "build_id INTEGER NOT NULL,"
                        + "stage_name TEXT NOT NULL,"
                        + "status TEXT,"
                        + "duration_ms INTEGER,"
                        + "seq INTEGER,"
                        + "FOREIGN KEY (build_id) REFERENCES builds(id))",
                "CREATE INDEX idx_builds_folder ON builds(job_folder)",
                "CREATE INDEX idx_builds_built_on ON builds(built_on)",
                "CREATE INDEX idx_builds_timestamp ON builds(timestamp_ms)",
                "CREATE INDEX idx_builds_result ON builds(result)",
                "CREATE INDEX idx_builds_triggered ON builds(triggered_by)",
                "CREATE INDEX idx_stages_build_id ON stages(build_id)");
    }

    @Override
    public String upsertBuildSql() {
        return "INSERT INTO builds "
                + "(job_full_name, job_folder, job_name, build_number, result, duration_ms, "
                + " queue_time_ms, timestamp_ms, built_on, node_labels, triggered_by) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(job_full_name, build_number) DO UPDATE SET "
                + " result=excluded.result, duration_ms=excluded.duration_ms, "
                + " queue_time_ms=excluded.queue_time_ms, timestamp_ms=excluded.timestamp_ms, "
                + " built_on=excluded.built_on, node_labels=excluded.node_labels, "
                + " triggered_by=excluded.triggered_by";
    }

    @Override
    public String periodBucketExpr(String groupBy) {
        String fmt = "hour".equals(groupBy) ? "%Y-%m-%d %H:00"
                : "week".equals(groupBy) ? "%Y-W%W" : "%Y-%m-%d";
        return "strftime('" + fmt + "', timestamp_ms/1000, 'unixepoch')";
    }

    @Override
    public String dayOfWeekExpr() {
        return "CAST(strftime('%w', timestamp_ms/1000,'unixepoch') AS INTEGER)";
    }

    @Override
    public String hourOfDayExpr() {
        return "CAST(strftime('%H', timestamp_ms/1000,'unixepoch') AS INTEGER)";
    }
}
