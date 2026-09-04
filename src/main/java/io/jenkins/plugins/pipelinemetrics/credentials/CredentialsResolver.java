package io.jenkins.plugins.pipelinemetrics.credentials;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.security.ACL;
import java.sql.SQLException;
import java.util.Collections;
import jenkins.model.Jenkins;

/**
 * Resolves a Jenkins credentials ID to the username/password pair backing an external database
 * connection. Storage backends persist only the credentials ID (never a username or password
 * directly), so there is nothing sensitive to leak through {@code GlobalConfiguration} XML or
 * JCasC export.
 */
public final class CredentialsResolver {

    private CredentialsResolver() {
    }

    /**
     * @throws SQLException if no credentials with this ID are visible to the system user, so
     *     callers building a {@code DataSource} get a clear, actionable failure instead of a
     *     null-pointer or a silently-empty username/password.
     */
    @NonNull
    public static StandardUsernamePasswordCredentials lookup(@NonNull String credentialsId) throws SQLException {
        StandardUsernamePasswordCredentials creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardUsernamePasswordCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM2,
                        Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));
        if (creds == null) {
            throw new SQLException(
                    "Pipeline Metrics: credentials '" + credentialsId + "' not found or not accessible");
        }
        return creds;
    }
}
