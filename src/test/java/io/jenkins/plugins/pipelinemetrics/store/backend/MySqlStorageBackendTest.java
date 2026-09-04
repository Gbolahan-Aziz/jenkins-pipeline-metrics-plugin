package io.jenkins.plugins.pipelinemetrics.store.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.SQLException;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class MySqlStorageBackendTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void jdbcUrlRequestsTlsByDefault() {
        MySqlStorageBackend backend =
                new MySqlStorageBackend("mysql.internal", 3306, "jenkins_metrics", "db-creds");
        assertEquals("jdbc:mariadb://mysql.internal:3306/jenkins_metrics?sslMode=TRUST", backend.jdbcUrl());
    }

    @Test
    public void jdbcUrlDisablesTlsWhenRequested() {
        MySqlStorageBackend backend =
                new MySqlStorageBackend("mysql.internal", 3306, "jenkins_metrics", "db-creds");
        backend.setUseSsl(false);
        assertEquals("jdbc:mariadb://mysql.internal:3306/jenkins_metrics?sslMode=DISABLED", backend.jdbcUrl());
    }

    @Test
    public void missingCredentialsFailClearlyWithoutTouchingNetwork() {
        MySqlStorageBackend backend =
                new MySqlStorageBackend("mysql.internal", 3306, "jenkins_metrics", "does-not-exist");
        SQLException e = assertThrows(SQLException.class, backend::createDataSource);
        assertTrue(e.getMessage().contains("does-not-exist"));
    }
}
