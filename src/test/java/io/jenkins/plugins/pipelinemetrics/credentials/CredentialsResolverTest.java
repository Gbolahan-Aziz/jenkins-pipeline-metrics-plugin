package io.jenkins.plugins.pipelinemetrics.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.util.Secret;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class CredentialsResolverTest {

    @Test
    void resolvesARegisteredCredential(JenkinsRule j) throws Exception {
        UsernamePasswordCredentialsImpl cred = new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM, "pipeline-metrics-db", "test db creds", "dbuser", "s3cret");
        SystemCredentialsProvider.getInstance().getCredentials().add(cred);
        SystemCredentialsProvider.getInstance().save();

        StandardUsernamePasswordCredentials resolved = CredentialsResolver.lookup("pipeline-metrics-db");

        assertEquals("dbuser", resolved.getUsername());
        assertEquals("s3cret", Secret.toString(resolved.getPassword()));
    }

    @Test
    void missingCredentialsIdFailsClearly(JenkinsRule j) {
        SQLException e = assertThrows(SQLException.class, () -> CredentialsResolver.lookup("does-not-exist"));
        assertTrue(e.getMessage().contains("does-not-exist"));
    }
}
