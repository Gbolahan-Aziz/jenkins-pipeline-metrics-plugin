package io.jenkins.plugins.pipelinemetrics.store.dialect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PostgresDialectTest {

    private final PostgresDialect dialect = new PostgresDialect();

    @Test
    public void idIsPostgresql() {
        assertEquals("postgresql", dialect.id());
    }

    @Test
    public void periodBucketExprCoversAllGroupings() {
        assertTrue(dialect.periodBucketExpr("hour").contains("HH24:00"));
        assertTrue(dialect.periodBucketExpr("day").contains("YYYY-MM-DD"));
        assertTrue(dialect.periodBucketExpr("week").contains("IW"));
    }

    @Test
    public void dayOfWeekAndHourUseExtract() {
        assertTrue(dialect.dayOfWeekExpr().contains("EXTRACT(DOW"));
        assertTrue(dialect.hourOfDayExpr().contains("EXTRACT(HOUR"));
    }

    @Test
    public void upsertUsesOnConflictExcluded() {
        String sql = dialect.upsertBuildSql();
        assertTrue(sql.contains("ON CONFLICT(job_full_name, build_number)"));
        assertTrue(sql.contains("EXCLUDED.result"));
    }

    @Test
    public void initialSchemaUsesBigserialAndUniqueConstraint() {
        String joined = String.join(";", dialect.initialSchemaStatements());
        assertTrue(joined.contains("BIGSERIAL"));
        assertTrue(joined.contains("UNIQUE(job_full_name, build_number)"));
    }

    @Test
    public void jobFullNameHasNoLengthLimit() {
        assertEquals(Integer.MAX_VALUE, dialect.maxJobFullNameLength());
    }
}
