package io.jenkins.plugins.pipelinemetrics.collect;

import hudson.model.AbstractBuild;
import hudson.model.Action;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.pipelinemetrics.model.BuildRecord;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

/** Maps a completed {@link Run} to a {@link BuildRecord}. */
public final class BuildFactory {

    private BuildFactory() {
    }

    public static BuildRecord fromRun(Run<?, ?> run) {
        BuildRecord b = new BuildRecord();

        String fullName = run.getParent().getFullName();
        b.setJobFullName(fullName);
        int slash = fullName.lastIndexOf('/');
        if (slash >= 0) {
            b.setJobFolder(fullName.substring(0, slash));
            b.setJobName(fullName.substring(slash + 1));
        } else {
            b.setJobFolder("");
            b.setJobName(fullName);
        }

        b.setBuildNumber(run.getNumber());
        Result result = run.getResult();
        b.setResult(result != null ? result.toString() : null);
        b.setDurationMs(run.getDuration());
        b.setTimestampMs(run.getStartTimeInMillis());
        b.setQueueTimeMs(extractQueueTimeMs(run));

        String builtOn = "";
        if (run instanceof AbstractBuild) {
            builtOn = ((AbstractBuild<?, ?>) run).getBuiltOnStr();
        }
        if (builtOn == null || builtOn.isEmpty()) {
            builtOn = "master";
        }
        b.setBuiltOn(builtOn);
        b.setNodeLabels(resolveNodeLabels(builtOn));

        b.setTriggeredBy(TriggerClassifier.classify(run));

        if (run instanceof WorkflowRun) {
            b.getStages().addAll(StageExtractor.extract((WorkflowRun) run));
        }
        return b;
    }

    private static String resolveNodeLabels(String builtOn) {
        try {
            if ("master".equals(builtOn) || "built-in".equals(builtOn)) {
                return "built-in";
            }
            Jenkins j = Jenkins.get();
            Node node = j.getNode(builtOn);
            if (node == null) {
                return "";
            }
            return node.getAssignedLabels().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(","));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Queue time is provided by the optional Metrics plugin via an action exposing
     * {@code getQueuingDurationMillis}. Read it reflectively so it stays an optional feature.
     */
    private static long extractQueueTimeMs(Run<?, ?> run) {
        for (Action a : run.getAllActions()) {
            try {
                Method m = a.getClass().getMethod("getQueuingDurationMillis");
                Object v = m.invoke(a);
                if (v instanceof Number) {
                    return ((Number) v).longValue();
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // action does not expose queue timing; try the next one
            }
        }
        return 0L;
    }
}
