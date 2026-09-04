package io.jenkins.plugins.pipelinemetrics.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.SQLException;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * Builds a Hikari-pooled connection to an embedded SQLite file, configured with the pragmas the
 * plugin has always used (WAL journal mode, NORMAL synchronous, a 5s busy timeout) and a
 * single-connection pool — which serializes access exactly as the original hand-held-connection
 * design did, just through the pool instead of a Java monitor. Shared by {@link MetricsStore}'s
 * file-based constructors and {@code SQLiteStorageBackend}.
 */
public final class SqliteConnectionFactory {

    private SqliteConnectionFactory() {
    }

    public static HikariDataSource forFile(File dbFile) throws SQLException {
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new SQLException("Could not create data directory " + parent);
        }
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqliteConfig.setBusyTimeout(5000);
        SQLiteDataSource sqliteDs = new SQLiteDataSource(sqliteConfig);
        sqliteDs.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDataSource(sqliteDs);
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setPoolName("pipeline-metrics-sqlite");
        return new HikariDataSource(hikariConfig);
    }
}
