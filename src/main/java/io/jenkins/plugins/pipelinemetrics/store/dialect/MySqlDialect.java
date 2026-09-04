package io.jenkins.plugins.pipelinemetrics.store.dialect;

import java.util.Arrays;
import java.util.List;

/**
 * MySQL/MariaDB dialect (one dialect and driver serve both — MariaDB's client speaks the MySQL
 * wire protocol against either server). MySQL cannot place a plain unique index on an unbounded
 * {@code TEXT} column without a prefix length, so {@code job_full_name} is a bounded
 * {@code VARCHAR(512)} here rather than {@code TEXT} as in the SQLite/Postgres dialects.
 */
public final class MySqlDialect implements SqlDialect {

    /** Matches the DDL's {@code VARCHAR(512)} bound on {@code job_full_name}. */
    public static final int JOB_FULL_NAME_MAX_LENGTH = 512;

    @Override
    public String id() {
        return "mysql";
    }

    @Override
    public int maxJobFullNameLength() {
        return JOB_FULL_NAME_MAX_LENGTH;
    }

    @Override
    public List<String> initialSchemaStatements() {
        return Arrays.asList(
                "CREATE TABLE builds ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "job_full_name VARCHAR(" + JOB_FULL_NAME_MAX_LENGTH + ") NOT NULL,"
                        + "job_folder TEXT NOT NULL,"
                        + "job_name TEXT NOT NULL,"
                        + "build_number INT NOT NULL,"
                        + "result VARCHAR(32),"
                        + "duration_ms BIGINT,"
                        + "queue_time_ms BIGINT,"
                        + "timestamp_ms BIGINT NOT NULL,"
                        + "built_on TEXT,"
                        + "node_labels TEXT,"
                        + "triggered_by VARCHAR(512) DEFAULT '',"
                        + "UNIQUE KEY uq_builds_job_number (job_full_name, build_number))",
                "CREATE TABLE stages ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "build_id BIGINT NOT NULL,"
                        + "stage_name TEXT NOT NULL,"
                        + "status VARCHAR(32),"
                        + "duration_ms BIGINT,"
                        + "seq INT,"
                        + "FOREIGN KEY (build_id) REFERENCES builds(id))",
                "CREATE INDEX idx_builds_folder ON builds(job_folder(255))",
                "CREATE INDEX idx_builds_built_on ON builds(built_on(255))",
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
                + "ON DUPLICATE KEY UPDATE "
                + " result=VALUES(result), duration_ms=VALUES(duration_ms), "
                + " queue_time_ms=VALUES(queue_time_ms), timestamp_ms=VALUES(timestamp_ms), "
                + " built_on=VALUES(built_on), node_labels=VALUES(node_labels), "
                + " triggered_by=VALUES(triggered_by)";
    }

    @Override
    public String periodBucketExpr(String groupBy) {
        String tsExpr = "FROM_UNIXTIME(timestamp_ms/1000)";
        if ("hour".equals(groupBy)) {
            return "DATE_FORMAT(" + tsExpr + ", '%Y-%m-%d %H:00')";
        }
        if ("week".equals(groupBy)) {
            // WEEK(..., 3) = ISO 8601 mode: Monday-first weeks, range 1-53.
            return "CONCAT(YEAR(" + tsExpr + "), '-W', LPAD(WEEK(" + tsExpr + ", 3), 2, '0'))";
        }
        return "DATE_FORMAT(" + tsExpr + ", '%Y-%m-%d')";
    }

    @Override
    public String dayOfWeekExpr() {
        // MySQL's DAYOFWEEK() is 1=Sunday..7=Saturday; shift to the 0=Sunday..6=Saturday
        // convention used by the SQLite/Postgres dialects and the dashboard's heatmap.
        return "(DAYOFWEEK(FROM_UNIXTIME(timestamp_ms/1000)) - 1)";
    }

    @Override
    public String hourOfDayExpr() {
        return "HOUR(FROM_UNIXTIME(timestamp_ms/1000))";
    }
}
