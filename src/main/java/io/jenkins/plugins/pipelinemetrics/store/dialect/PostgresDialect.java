package io.jenkins.plugins.pipelinemetrics.store.dialect;

import java.util.Arrays;
import java.util.List;

/** PostgreSQL dialect: an external, multi-writer-safe backend for shared/network storage or HA. */
public final class PostgresDialect implements SqlDialect {

    @Override
    public String id() {
        return "postgresql";
    }

    @Override
    public List<String> initialSchemaStatements() {
        return Arrays.asList(
                "CREATE TABLE builds ("
                        + "id BIGSERIAL PRIMARY KEY,"
                        + "job_full_name TEXT NOT NULL,"
                        + "job_folder TEXT NOT NULL,"
                        + "job_name TEXT NOT NULL,"
                        + "build_number INTEGER NOT NULL,"
                        + "result TEXT,"
                        + "duration_ms BIGINT,"
                        + "queue_time_ms BIGINT,"
                        + "timestamp_ms BIGINT NOT NULL,"
                        + "built_on TEXT DEFAULT '',"
                        + "node_labels TEXT DEFAULT '',"
                        + "triggered_by TEXT DEFAULT '',"
                        + "UNIQUE(job_full_name, build_number))",
                "CREATE TABLE stages ("
                        + "id BIGSERIAL PRIMARY KEY,"
                        + "build_id BIGINT NOT NULL,"
                        + "stage_name TEXT NOT NULL,"
                        + "status TEXT,"
                        + "duration_ms BIGINT,"
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
                + " result=EXCLUDED.result, duration_ms=EXCLUDED.duration_ms, "
                + " queue_time_ms=EXCLUDED.queue_time_ms, timestamp_ms=EXCLUDED.timestamp_ms, "
                + " built_on=EXCLUDED.built_on, node_labels=EXCLUDED.node_labels, "
                + " triggered_by=EXCLUDED.triggered_by";
    }

    @Override
    public String periodBucketExpr(String groupBy) {
        String tsExpr = "to_timestamp(timestamp_ms / 1000.0)";
        if ("hour".equals(groupBy)) {
            return "to_char(" + tsExpr + ", 'YYYY-MM-DD HH24:00')";
        }
        if ("week".equals(groupBy)) {
            return "to_char(" + tsExpr + ", 'IYYY-\"W\"IW')";
        }
        return "to_char(" + tsExpr + ", 'YYYY-MM-DD')";
    }

    @Override
    public String dayOfWeekExpr() {
        return "CAST(EXTRACT(DOW FROM to_timestamp(timestamp_ms / 1000.0)) AS INTEGER)";
    }

    @Override
    public String hourOfDayExpr() {
        return "CAST(EXTRACT(HOUR FROM to_timestamp(timestamp_ms / 1000.0)) AS INTEGER)";
    }
}
