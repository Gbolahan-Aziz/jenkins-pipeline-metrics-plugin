package io.jenkins.plugins.pipelinemetrics.credentials;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.util.Secret;
import java.sql.SQLException;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class CredentialsResolverTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void resolvesARegisteredCredential() throws Exception {
        UsernamePasswordCredentialsImpl cred = new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM, "pipeline-metrics-db", "test db creds", "dbuser", "s3cret");
        SystemCredentialsProvider.getInstance().getCredentials().add(cred);
        SystemCredentialsProvider.getInstance().save();

        StandardUsernamePasswordCredentials resolved = CredentialsResolver.lookup("pipeline-metrics-db");

        assertEquals("dbuser", resolved.getUsername());
        assertEquals("s3cret", Secret.toString(resolved.getPassword()));
    }

    @Test
    public void missingCredentialsIdFailsClearly() {
        SQLException e = assertThrows(SQLException.class, () -> CredentialsResolver.lookup("does-not-exist"));
        org.junit.Assert.assertTrue(e.getMessage().contains("does-not-exist"));
    }
}
