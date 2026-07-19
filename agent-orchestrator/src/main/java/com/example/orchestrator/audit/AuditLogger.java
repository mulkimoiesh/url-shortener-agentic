package com.example.orchestrator.audit;

import com.example.orchestrator.graph.StageResult;
import com.example.orchestrator.state.Actor;
import com.example.orchestrator.state.Stage;
import com.example.orchestrator.state.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Audit-grade observability: every node execution is appended to the run's
 * decision log (queryable via the API) AND emitted as a structured log line
 * (queryable via your log aggregator in a real deployment).
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    public void logStageExecution(WorkflowState state, Stage stage, StageResult result, long durationMs) {
        String summary = "[%s, %dms] %s".formatted(result.outcome(), durationMs, result.summary());
        state.addDecision(stage, Actor.SYSTEM, summary);
        state.recordStageDuration(stage, durationMs);

        log.info("runId={} stage={} outcome={} durationMs={} summary=\"{}\"",
                state.getRunId(), stage, result.outcome(), durationMs, result.summary());
    }

    public void logHumanDecision(WorkflowState state, Stage stage, String approvedBy, String notes) {
        String summary = "Approved by %s. %s".formatted(approvedBy, notes == null ? "" : notes);
        state.addDecision(stage, Actor.HUMAN, summary.trim());

        log.info("runId={} stage={} actor=HUMAN approvedBy={} notes=\"{}\"",
                state.getRunId(), stage, approvedBy, notes);
    }
}
