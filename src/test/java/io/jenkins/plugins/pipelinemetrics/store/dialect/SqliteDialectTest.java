package io.jenkins.plugins.pipelinemetrics.store.dialect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SqliteDialectTest {

    private final SqliteDialect dialect = new SqliteDialect();

    @Test
    public void idIsSqlite() {
        assertEquals("sqlite", dialect.id());
    }

    @Test
    public void periodBucketExprCoversAllGroupings() {
        assertTrue(dialect.periodBucketExpr("hour").contains("%H:00"));
        assertTrue(dialect.periodBucketExpr("day").contains("%Y-%m-%d"));
        assertTrue(dialect.periodBucketExpr("week").contains("%W"));
    }

    @Test
    public void dayOfWeekAndHourExpressionsCastToInteger() {
        assertTrue(dialect.dayOfWeekExpr().startsWith("CAST("));
        assertTrue(dialect.hourOfDayExpr().startsWith("CAST("));
    }

    @Test
    public void upsertSqlTargetsNaturalKey() {
        String sql = dialect.upsertBuildSql();
        assertTrue(sql.contains("ON CONFLICT(job_full_name, build_number)"));
        assertTrue(sql.contains("INSERT INTO builds"));
    }

    @Test
    public void initialSchemaCreatesBuildsAndStagesTables() {
        String joined = String.join(";", dialect.initialSchemaStatements());
        assertTrue(joined.contains("CREATE TABLE builds"));
        assertTrue(joined.contains("CREATE TABLE stages"));
        assertTrue(joined.contains("UNIQUE(job_full_name, build_number)"));
    }
}
