package io.jenkins.plugins.pipelinemetrics.store.dialect;

import java.util.List;

/**
 * Isolates the handful of SQL fragments that differ across storage engines: schema DDL, the
 * build upsert statement, and the date-bucketing expressions used by the trends and heatmap
 * queries. Everything else in {@code MetricsStore}/{@code MetricsQueryService} is portable SQL
 * and stays engine-agnostic.
 */
public interface SqlDialect {

    /** Short identifier used in logs and migration/monitor messages, e.g. {@code "sqlite"}. */
    String id();

    /** DDL statements run once, in order, to create the schema from empty. */
    List<String> initialSchemaStatements();

    /**
     * Upsert statement for one build row: 11 positional params in the same order as
     * {@code MetricsStore#upsertBuild}'s column list, keyed on {@code (job_full_name, build_number)}.
     */
    String upsertBuildSql();

    /**
     * SQL expression bucketing {@code timestamp_ms} into a period label for the trends query.
     *
     * @param groupBy one of {@code "hour"}, {@code "day"}, {@code "week"} (validated by the caller)
     */
    String periodBucketExpr(String groupBy);

    /** SQL expression yielding the day of week (0=Sunday .. 6=Saturday) from {@code timestamp_ms}. */
    String dayOfWeekExpr();

    /** SQL expression yielding the hour of day (0-23) from {@code timestamp_ms}. */
    String hourOfDayExpr();

    /**
     * Maximum length this dialect allows for {@code job_full_name} (the natural upsert key).
     * SQLite/Postgres store it as unbounded {@code TEXT}; MySQL bounds it to index it. Callers
     * writing a longer job path should skip the record rather than truncate it, since truncation
     * risks colliding two different jobs' paths onto the same stored key.
     */
    default int maxJobFullNameLength() {
        return Integer.MAX_VALUE;
    }

    /**
     * Maximum length this dialect allows for {@code triggered_by} (e.g. {@code "user:<id>"}).
     * Same rationale as {@link #maxJobFullNameLength()} — bounded only where the column itself
     * is bounded (MySQL), and skipped rather than truncated for the same collision reason.
     */
    default int maxTriggeredByLength() {
        return Integer.MAX_VALUE;
    }
}
