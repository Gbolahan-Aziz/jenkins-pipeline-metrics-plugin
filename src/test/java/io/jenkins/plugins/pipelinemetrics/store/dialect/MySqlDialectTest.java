package io.jenkins.plugins.pipelinemetrics.store.dialect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MySqlDialectTest {

    private final MySqlDialect dialect = new MySqlDialect();

    @Test
    public void idIsMysql() {
        assertEquals("mysql", dialect.id());
    }

    @Test
    public void periodBucketExprCoversAllGroupings() {
        assertTrue(dialect.periodBucketExpr("hour").contains("%H:00"));
        assertTrue(dialect.periodBucketExpr("day").contains("%Y-%m-%d"));
        assertTrue(dialect.periodBucketExpr("week").contains("WEEK("));
    }

    @Test
    public void dayOfWeekShiftsToZeroIndexedSunday() {
        // MySQL's DAYOFWEEK() is 1=Sunday; the dialect must shift it to 0=Sunday to match
        // SQLite/Postgres and the dashboard's heatmap convention.
        assertTrue(dialect.dayOfWeekExpr().contains("DAYOFWEEK") && dialect.dayOfWeekExpr().contains("- 1"));
    }

    @Test
    public void upsertUsesOnDuplicateKeyUpdate() {
        String sql = dialect.upsertBuildSql();
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(sql.contains("VALUES(result)"));
    }

    @Test
    public void jobFullNameIsBoundedForIndexing() {
        assertEquals(MySqlDialect.JOB_FULL_NAME_MAX_LENGTH, dialect.maxJobFullNameLength());
        String joined = String.join(";", dialect.initialSchemaStatements());
        assertTrue(joined.contains("VARCHAR(" + MySqlDialect.JOB_FULL_NAME_MAX_LENGTH + ")"));
        assertTrue(joined.contains("UNIQUE KEY uq_builds_job_number"));
    }
}
