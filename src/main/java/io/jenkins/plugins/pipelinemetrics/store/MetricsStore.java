package io.jenkins.plugins.pipelinemetrics.store;

import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.init.Terminator;
import io.jenkins.plugins.pipelinemetrics.model.BuildRecord;
import io.jenkins.plugins.pipelinemetrics.model.StageRecord;
import io.jenkins.plugins.pipelinemetrics.store.dialect.SqlDialect;
import io.jenkins.plugins.pipelinemetrics.store.dialect.SqliteDialect;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import jenkins.model.Jenkins;

/**
 * Persistence for build and stage metrics, backed by a pooled JDBC {@link DataSource} whose
 * schema/queries follow this store's {@link SqlDialect}. The default backend is embedded SQLite
 * under {@code $JENKINS_HOME/pipeline-metrics/metrics.db}; other backends are layered on in
 * later phases. Every read/write borrows its own connection from the pool and is
 * self-contained (its own transaction where relevant), so no additional synchronization is
 * needed here — for SQLite the pool is sized to 1 connection, which serializes access exactly
 * as the previous hand-held-connection design did, just via the pool instead of a Java monitor.
 */
public class MetricsStore {

    private static final Logger LOGGER = Logger.getLogger(MetricsStore.class.getName());
    private static final int SCHEMA_VERSION = 1;

    private static MetricsStore instance;

    @CheckForNull
    private final File dbFile;
    private final SqlDialect dialect;
    private HikariDataSource dataSource;
    private volatile boolean available;
    private volatile String unavailableReason;

    public MetricsStore(@NonNull File dbFile) {
        this(dbFile, new SqliteDialect());
    }

    public MetricsStore(@NonNull File dbFile, @NonNull SqlDialect dialect) {
        this.dbFile = dbFile;
        this.dialect = dialect;
    }

    /**
     * For a backend that builds its own pooled connection (an external database, or embedded
     * SQLite via {@code SQLiteStorageBackend}) rather than a plain file path.
     */
    public MetricsStore(@NonNull HikariDataSource dataSource, @NonNull SqlDialect dialect) {
        this.dbFile = null;
        this.dialect = dialect;
        this.dataSource = dataSource;
    }

    /** The SQL dialect this store's schema/queries are written against. */
    @NonNull
    public SqlDialect dialect() {
        return dialect;
    }

    /**
     * Singleton bound to the Jenkins home directory. Defaults to local SQLite at the historical
     * fixed path; {@link #reconfigure} swaps in whatever backend is actually configured, once
     * {@code PipelineMetricsGlobalConfig} has had a chance to run (it calls {@code reconfigure}
     * from its own constructor, so this default only wins the race on a very first boot before
     * that has happened, or when the configured backend is the SQLite default anyway).
     */
    public static synchronized MetricsStore get() {
        if (instance == null) {
            File dir = new File(Jenkins.get().getRootDir(), "pipeline-metrics");
            instance = new MetricsStore(new File(dir, "metrics.db"));
            instance.init();
        }
        return instance;
    }

    /**
     * Rebuilds the singleton from a freshly built pool and dialect, and atomically swaps it in.
     * Used by {@code PipelineMetricsGlobalConfig} whenever the configured storage backend
     * changes (UI, JCasC, or on load at startup). If the new backend fails to initialize, the
     * previous store is left in place and this method returns {@code false} — the admin's
     * change did not take effect and the failure is logged.
     */
    public static synchronized boolean reconfigure(@NonNull HikariDataSource newDataSource, @NonNull SqlDialect newDialect) {
        MetricsStore next = new MetricsStore(newDataSource, newDialect);
        next.init();
        if (!next.isAvailable()) {
            LOGGER.log(Level.SEVERE,
                    "New Pipeline Metrics storage backend ({0}) failed to initialize: {1} — keeping the previous backend",
                    new Object[] {newDialect.id(), next.getUnavailableReason()});
            next.close();
            return false;
        }
        MetricsStore old = instance;
        instance = next;
        if (old != null) {
            old.close();
        }
        return true;
    }

    /** Closes and forgets the singleton so the next {@link #get()} rebuilds it from scratch. */
    @Terminator
    public static synchronized void shutdown() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    public synchronized void init() {
        try {
            if (dbFile != null) {
                // File-based construction: this store owns and (re)builds its own SQLite pool.
                if (dataSource != null) {
                    dataSource.close();
                }
                dataSource = SqliteConnectionFactory.forFile(dbFile);
            }
            // Else: a pre-built pooled DataSource was supplied directly (see the HikariDataSource
            // constructor) — nothing to build here, just initialize the schema on it below.

            try (Connection c = dataSource.getConnection()) {
                ensureSchemaVersionTable(c);
                migrate(c);
            }
            available = true;
            unavailableReason = null;
            LOGGER.log(Level.INFO, "Pipeline Metrics store ready ({0}): {1}",
                    new Object[] {dialect.id(), dbFile != null ? dbFile.getAbsolutePath() : "externally supplied pool"});
        } catch (SQLException | RuntimeException e) {
            available = false;
            unavailableReason = e.getMessage();
            LOGGER.log(Level.SEVERE, "Failed to initialize pipeline metrics store", e);
        }
    }

    public boolean isAvailable() {
        return available;
    }

    @CheckForNull
    public String getUnavailableReason() {
        return unavailableReason;
    }

    /**
     * The {@code schema_version} table itself uses portable DDL (no auto-increment) and is
     * created up front, independent of the dialect, so {@link #migrate} always has somewhere
     * to read/write the current version from.
     */
    private void ensureSchemaVersionTable(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }
    }

    /**
     * Linear, version-gated migration: version 0 (no row yet) means an empty database, so the
     * dialect's initial schema is applied as migration #1. Later schema changes become
     * migration #2, #3, ... applied in order up to {@link #SCHEMA_VERSION}. Because this is
     * gated on the stored version rather than using "IF NOT EXISTS" DDL, dialects never need to
     * express idempotent-create syntax (which MySQL's CREATE INDEX does not support anyway).
     */
    private void migrate(Connection c) throws SQLException {
        int current;
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
            current = rs.next() ? rs.getInt(1) : 0;
        }
        if (current == 0) {
            try (Statement st = c.createStatement()) {
                for (String ddl : dialect.initialSchemaStatements()) {
                    st.executeUpdate(ddl);
                }
                st.executeUpdate("DELETE FROM schema_version");
                st.executeUpdate("INSERT INTO schema_version(version) VALUES (" + SCHEMA_VERSION + ")");
            }
            return;
        }
        // Future migrations: apply steps from `current` up to SCHEMA_VERSION here.
        if (current > SCHEMA_VERSION) {
            LOGGER.log(Level.WARNING,
                    "Metrics store schema version {0} is newer than supported {1}",
                    new Object[] {current, SCHEMA_VERSION});
        }
    }

    /** Atomically upsert the build and replace its stages. */
    public void upsertBuild(@NonNull BuildRecord b) {
        if (!available) {
            LOGGER.log(Level.WARNING, "Metrics store unavailable, dropping build {0}#{1}",
                    new Object[] {b.getJobFullName(), b.getBuildNumber()});
            return;
        }
        if (b.getJobFullName() != null && b.getJobFullName().length() > dialect.maxJobFullNameLength()) {
            // Truncating would risk colliding two different jobs' paths onto the same key.
            LOGGER.log(Level.WARNING,
                    "Job path exceeds the {0} dialect''s {1}-character limit, dropping build {2}#{3}",
                    new Object[] {dialect.id(), dialect.maxJobFullNameLength(),
                            b.getJobFullName(), b.getBuildNumber()});
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(dialect.upsertBuildSql())) {
                    ps.setString(1, b.getJobFullName());
                    ps.setString(2, b.getJobFolder());
                    ps.setString(3, b.getJobName());
                    ps.setInt(4, b.getBuildNumber());
                    ps.setString(5, b.getResult());
                    ps.setLong(6, b.getDurationMs());
                    ps.setLong(7, b.getQueueTimeMs());
                    ps.setLong(8, b.getTimestampMs());
                    ps.setString(9, b.getBuiltOn());
                    ps.setString(10, b.getNodeLabels());
                    ps.setString(11, b.getTriggeredBy());
                    ps.executeUpdate();
                }
                long buildId;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id FROM builds WHERE job_full_name=? AND build_number=?")) {
                    ps.setString(1, b.getJobFullName());
                    ps.setInt(2, b.getBuildNumber());
                    try (ResultSet rs = ps.executeQuery()) {
                        buildId = rs.next() ? rs.getLong(1) : -1;
                    }
                }
                if (buildId > 0) {
                    try (PreparedStatement del = c.prepareStatement("DELETE FROM stages WHERE build_id=?")) {
                        del.setLong(1, buildId);
                        del.executeUpdate();
                    }
                    if (!b.getStages().isEmpty()) {
                        try (PreparedStatement ins = c.prepareStatement(
                                "INSERT INTO stages (build_id, stage_name, status, duration_ms, seq) "
                                + "VALUES (?,?,?,?,?)")) {
                            for (StageRecord s : b.getStages()) {
                                ins.setLong(1, buildId);
                                ins.setString(2, s.getStageName());
                                ins.setString(3, s.getStatus());
                                ins.setLong(4, s.getDurationMs());
                                ins.setInt(5, s.getSeq());
                                ins.addBatch();
                            }
                            ins.executeBatch();
                        }
                    }
                }
                c.commit();
            } catch (SQLException e) {
                rollbackQuietly(c);
                LOGGER.log(Level.WARNING,
                        "Failed to store build " + b.getJobFullName() + "#" + b.getBuildNumber(), e);
            } finally {
                restoreAutoCommit(c);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to obtain a connection while storing build "
                            + b.getJobFullName() + "#" + b.getBuildNumber(), e);
        }
    }

    /** Delete builds (and their stages) older than the cutoff. Returns rows removed. */
    public int deleteOlderThan(long cutoffMs) {
        if (!available) {
            return 0;
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM stages WHERE build_id IN (SELECT id FROM builds WHERE timestamp_ms < ?)")) {
                    del.setLong(1, cutoffMs);
                    del.executeUpdate();
                }
                int removed;
                try (PreparedStatement del = c.prepareStatement("DELETE FROM builds WHERE timestamp_ms < ?")) {
                    del.setLong(1, cutoffMs);
                    removed = del.executeUpdate();
                }
                c.commit();
                return removed;
            } catch (SQLException e) {
                rollbackQuietly(c);
                LOGGER.log(Level.WARNING, "Retention delete failed", e);
                return 0;
            } finally {
                restoreAutoCommit(c);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Retention delete failed to obtain a connection", e);
            return 0;
        }
    }

    /** Total number of stored builds (0 when empty or unavailable). */
    public long buildCount() {
        if (!available) {
            return 0;
        }
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM builds")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "buildCount failed", e);
            return 0;
        }
    }

    /** Run a read (or read/write) query on a connection borrowed from the pool. */
    public <T> T query(@NonNull ConnectionCallback<T> cb) throws SQLException {
        if (!available) {
            throw new SQLException("Metrics store is unavailable: " + unavailableReason);
        }
        try (Connection c = dataSource.getConnection()) {
            return cb.run(c);
        }
    }

    /** Releases the connection pool. Safe to call more than once. */
    public synchronized void close() {
        available = false;
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "Rollback failed", ex);
        }
    }

    private void restoreAutoCommit(Connection c) {
        try {
            c.setAutoCommit(true);
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "Could not restore autocommit", ex);
        }
    }
}
