package io.jenkins.plugins.pipelinemetrics.collect;

import io.jenkins.plugins.pipelinemetrics.model.StageRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

/**
 * Extracts pipeline stage timings from a completed {@link WorkflowRun}. Stage boundaries are
 * detected via {@link LabelAction} markers; durations are derived from {@link TimingAction}.
 * Defensive: any failure yields an empty list rather than aborting collection.
 */
public final class StageExtractor {

    private static final Logger LOGGER = Logger.getLogger(StageExtractor.class.getName());

    private StageExtractor() {
    }

    public static List<StageRecord> extract(WorkflowRun run) {
        List<StageRecord> result = new ArrayList<>();
        try {
            FlowExecution exec = run.getExecution();
            if (exec == null) {
                return result;
            }
            List<FlowNode> stageStarts = new ArrayList<>();
            FlowGraphWalker walker = new FlowGraphWalker(exec);
            for (FlowNode node : walker) {
                LabelAction label = node.getAction(LabelAction.class);
                if (label != null) {
                    stageStarts.add(node);
                }
            }
            stageStarts.sort(Comparator.comparingLong(StageExtractor::startTime));

            long runEnd = run.getStartTimeInMillis() + run.getDuration();
            for (int i = 0; i < stageStarts.size(); i++) {
                FlowNode node = stageStarts.get(i);
                LabelAction label = node.getAction(LabelAction.class);
                String name = label != null ? label.getDisplayName() : node.getDisplayName();
                long start = startTime(node);
                long end = (i + 1 < stageStarts.size()) ? startTime(stageStarts.get(i + 1)) : runEnd;
                long duration = Math.max(0, end - start);
                String status = node.getAction(ErrorAction.class) != null ? "FAILED" : "SUCCESS";
                result.add(new StageRecord(name, status, duration, i));
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not extract stages for " + run.getFullDisplayName(), e);
            return new ArrayList<>();
        }
        return result;
    }

    private static long startTime(FlowNode node) {
        TimingAction t = node.getAction(TimingAction.class);
        return t != null ? t.getStartTime() : 0L;
    }
}
