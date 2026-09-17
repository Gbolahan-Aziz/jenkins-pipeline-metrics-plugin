package io.jenkins.plugins.pipelinemetrics.collect;

import io.jenkins.plugins.pipelinemetrics.model.StageRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;

/**
 * Extracts pipeline stage timings from a completed {@link WorkflowRun}. Stage boundaries are
 * detected via {@link LabelAction} markers; each stage's own end time comes from its matching
 * {@link BlockEndNode} (every block-scoped step, {@code stage(...)} included, opens with a
 * block-start node and closes with a block-end node) rather than from the next stage's start
 * time — the latter looks reasonable for a linear pipeline but silently corrupts durations for
 * any stage inside a {@code parallel} block, since parallel branches are siblings, not a
 * sequence, and one branch's start has no real relationship to another's end. Defensive: any
 * failure yields an empty list rather than aborting collection.
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
            // Maps a block-start node to the time its matching BlockEndNode was reached, so each
            // stage's duration comes from its own block boundary, not a sibling's start time.
            Map<FlowNode, Long> blockEndTimes = new HashMap<>();
            FlowGraphWalker walker = new FlowGraphWalker(exec);
            for (FlowNode node : walker) {
                if (isStageOrParallelBranch(node)) {
                    stageStarts.add(node);
                }
                if (node instanceof BlockEndNode) {
                    FlowNode startNode = ((BlockEndNode<?>) node).getStartNode();
                    blockEndTimes.put(startNode, startTime(node));
                }
            }
            stageStarts.sort(Comparator.comparingLong(StageExtractor::startTime));

            long runEnd = run.getStartTimeInMillis() + run.getDuration();
            for (int i = 0; i < stageStarts.size(); i++) {
                FlowNode node = stageStarts.get(i);
                LabelAction label = node.getAction(LabelAction.class);
                String name = label != null ? label.getDisplayName() : node.getDisplayName();
                long start = startTime(node);
                // Fall back to the run's own end only if this stage's block never closed (e.g.
                // an aborted/crashed run leaving an incomplete graph) — not to a sibling's start.
                Long ownEnd = blockEndTimes.get(node);
                long end = ownEnd != null ? ownEnd : runEnd;
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

    /**
     * Whether a flow node opens a stage or a parallel branch.
     *
     * <p>A {@link LabelAction} alone is not enough: a step given a {@code label}, such as
     * {@code sh label: 'x', script: '...'}, carries one too but is an ordinary step. A stage or
     * branch is always a block that opens a body, so the node must be a {@link BlockStartNode}. A
     * parallel branch is then identified by its {@link ThreadNameAction}, and a stage by its step's
     * function name. When a build loaded from disk no longer has a descriptor for its step, for
     * example because the plugin that provided it was removed, the labelled block is kept, so old
     * builds do not silently lose their stages.
     */
    private static boolean isStageOrParallelBranch(FlowNode node) {
        if (!(node instanceof BlockStartNode) || node.getAction(LabelAction.class) == null) {
            return false;
        }
        if (node.getAction(ThreadNameAction.class) != null) {
            return true;
        }
        if (node instanceof StepNode stepNode) {
            StepDescriptor descriptor = stepNode.getDescriptor();
            return descriptor == null || "stage".equals(descriptor.getFunctionName());
        }
        return false;
    }

    private static long startTime(FlowNode node) {
        TimingAction t = node.getAction(TimingAction.class);
        return t != null ? t.getStartTime() : 0L;
    }
}
