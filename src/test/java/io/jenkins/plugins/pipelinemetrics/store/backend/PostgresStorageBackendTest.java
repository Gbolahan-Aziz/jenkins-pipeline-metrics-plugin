package io.jenkins.plugins.pipelinemetrics.store.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PostgresStorageBackendTest {

    @Test
    void jdbcUrlRequestsTlsByDefault(JenkinsRule j) {
        PostgresStorageBackend backend =
                new PostgresStorageBackend("pg.internal", 5432, "jenkins_metrics", "db-creds");
        assertEquals("jdbc:postgresql://pg.internal:5432/jenkins_metrics?ssl=true&sslmode=require",
                backend.jdbcUrl());
    }

    @Test
    void jdbcUrlDisablesTlsWhenRequested(JenkinsRule j) {
        PostgresStorageBackend backend =
                new PostgresStorageBackend("pg.internal", 5432, "jenkins_metrics", "db-creds");
        backend.setUseSsl(false);
        assertEquals("jdbc:postgresql://pg.internal:5432/jenkins_metrics?ssl=false&sslmode=disable",
                backend.jdbcUrl());
    }

    @Test
    void poolSizeIsValidated(JenkinsRule j) {
        PostgresStorageBackend backend =
                new PostgresStorageBackend("pg.internal", 5432, "jenkins_metrics", "db-creds");
        assertThrows(IllegalArgumentException.class, () -> backend.setMaxPoolSize(0));
        assertThrows(IllegalArgumentException.class, () -> backend.setMaxPoolSize(21));
    }

    @Test
    void missingCredentialsFailClearlyWithoutTouchingNetwork(JenkinsRule j) {
        PostgresStorageBackend backend =
                new PostgresStorageBackend("pg.internal", 5432, "jenkins_metrics", "does-not-exist");
        SQLException e = assertThrows(SQLException.class, backend::createDataSource);
        assertTrue(e.getMessage().contains("does-not-exist"));
    }
}
