package io.jenkins.plugins.pipelinemetrics.collect;

import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Run;
import java.util.List;

/**
 * Classifies a build's cause into a Trigger_Origin string:
 * {@code user:<id>}, {@code scm}, {@code timer}, {@code upstream}, or {@code unknown}.
 */
public final class TriggerClassifier {

    private TriggerClassifier() {
    }

    public static String classify(Run<?, ?> run) {
        for (CauseAction ca : run.getActions(CauseAction.class)) {
            List<Cause> causes = ca.getCauses();
            if (causes == null) {
                continue;
            }
            for (Cause cause : causes) {
                String origin = classifyCause(cause);
                if (origin != null) {
                    return origin;
                }
            }
        }
        return "unknown";
    }

    private static String classifyCause(Cause cause) {
        if (cause instanceof Cause.UserIdCause) {
            String id = ((Cause.UserIdCause) cause).getUserId();
            return "user:" + (id == null ? "unknown" : id);
        }
        if (cause instanceof Cause.UpstreamCause) {
            return "upstream";
        }
        String cn = cause.getClass().getName();
        if (cn.contains("TimerTrigger")) {
            return "timer";
        }
        if (cn.contains("SCMTrigger") || cn.contains("GitHubPush") || cn.contains("SCMTriggerCause")
                || cn.contains("GitHubPushCause") || cn.contains("BranchEventCause")
                || cn.contains("BranchIndexingCause")) {
            return "scm";
        }
        return null;
    }
}
