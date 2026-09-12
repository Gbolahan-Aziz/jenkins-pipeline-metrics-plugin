package io.jenkins.plugins.pipelinemetrics.store.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class MySqlStorageBackendTest {

    @Test
    void jdbcUrlRequestsTlsByDefault(JenkinsRule j) {
        MySqlStorageBackend backend =
                new MySqlStorageBackend("mysql.internal", 3306, "jenkins_metrics", "db-creds");
        assertEquals("jdbc:mariadb://mysql.internal:3306/jenkins_metrics?sslMode=TRUST", backend.jdbcUrl());
    }

    @Test
    void jdbcUrlDisablesTlsWhenRequested(JenkinsRule j) {
        MySqlStorageBackend backend =
                new MySqlStorageBackend("mysql.internal", 3306, "jenkins_metrics", "db-creds");
        backend.setUseSsl(false);
        assertEquals("jdbc:mariadb://mysql.internal:3306/jenkins_metrics?sslMode=DISABLED", backend.jdbcUrl());
    }

    @Test
    void missingCredentialsFailClearlyWithoutTouchingNetwork(JenkinsRule j) {
        MySqlStorageBackend backend =
                new MySqlStorageBackend("mysql.internal", 3306, "jenkins_metrics", "does-not-exist");
        SQLException e = assertThrows(SQLException.class, backend::createDataSource);
        assertTrue(e.getMessage().contains("does-not-exist"));
    }
}
