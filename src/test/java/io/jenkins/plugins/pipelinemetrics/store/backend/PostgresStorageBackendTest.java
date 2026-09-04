package io.jenkins.plugins.pipelinemetrics.store.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.SQLException;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class PostgresStorageBackendTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void jdbcUrlRequestsTlsByDefault() {
        PostgresStorageBackend backend =
                new PostgresStorageBackend("pg.internal", 5432, "jenkins_metrics", "db-creds");
        assertEquals("jdbc:postgresql://pg.internal:5432/jenkins_metrics?ssl=true&sslmode=require",
                backend.jdbcUrl());
    }

    @Test
    public void jdbcUrlDisablesTlsWhenRequested() {
        PostgresStorageBackend backend =
                new PostgresStorageBackend("pg.internal", 5432, "jenkins_metrics", "db-creds");
        backend.setUseSsl(false);
        assertEquals("jdbc:postgresql://pg.internal:5432/jenkins_metrics?ssl=false&sslmode=disable",
                backend.jdbcUrl());
    }

    @Test
    public void poolSizeIsValidated() {
        PostgresStorageBackend backend =
                new PostgresStorageBackend("pg.internal", 5432, "jenkins_metrics", "db-creds");
        assertThrows(IllegalArgumentException.class, () -> backend.setMaxPoolSize(0));
        assertThrows(IllegalArgumentException.class, () -> backend.setMaxPoolSize(21));
    }

    @Test
    public void missingCredentialsFailClearlyWithoutTouchingNetwork() {
        PostgresStorageBackend backend =
                new PostgresStorageBackend("pg.internal", 5432, "jenkins_metrics", "does-not-exist");
        SQLException e = assertThrows(SQLException.class, backend::createDataSource);
        assertTrue(e.getMessage().contains("does-not-exist"));
    }
}
