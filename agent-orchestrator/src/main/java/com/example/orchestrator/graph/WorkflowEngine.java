package com.example.orchestrator.graph;

import com.example.orchestrator.audit.AuditLogger;
import com.example.orchestrator.state.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The orchestration layer. This is deliberately NOT "call node1, then node2,
 * then node3" - it is a routing table over Stage transitions with three
 * non-linear behaviors baked in:
 *
 *  1. GATE stages (REQUIREMENTS, ARCHITECTURE, RELEASE) stop the engine and
 *     wait for a human decision (approveGate) before continuing.
 *  2. IMPLEMENTATION or TESTING failure routes (or stays) at IMPLEMENTATION
 *     (a bounded retry loop), up to MAX_IMPLEMENTATION_RETRIES, then trips
 *     a rollback/safe-stop rather than retrying forever. This covers both
 *     "the plan failed brownfield validation" (caught before any write) and
 *     "the real test suite failed" (caught after a write, in the workspace).
 *  3. GUARDRAILS failure routes to a BLOCKED state that requires an explicit
 *     human resolution (resolveBlock) - it does not silently continue.
 */
@Component
public class WorkflowEngine {

    private static final int MAX_IMPLEMENTATION_RETRIES = 2;

    /** Happy-path stage order, used to compute "what's next" when a stage succeeds cleanly. */
    private static final List<Stage> PIPELINE_ORDER = List.of(
            Stage.REQUIREMENTS,
            Stage.ARCHITECTURE,
            Stage.IMPLEMENTATION,
            Stage.TESTING,
            Stage.GUARDRAILS,
            Stage.DOCUMENTATION,
            Stage.RELEASE,
            Stage.COMPLETED
    );

    private final Map<Stage, WorkflowNode> nodes;
    private final AuditLogger auditLogger;

    public WorkflowEngine(List<WorkflowNode> registeredNodes, AuditLogger auditLogger) {
        this.nodes = registeredNodes.stream()
                .collect(java.util.stream.Collectors.toMap(WorkflowNode::stage, n -> n));
        this.auditLogger = auditLogger;
    }

    /** Creates a new run and executes until the first gate, block, or terminal state. */
    public WorkflowState startRun(String rawRequirement, ScenarioType scenarioType) {
        WorkflowState state = new WorkflowState(rawRequirement, scenarioType);
        state.addDecision(Stage.REQUIREMENTS, Actor.SYSTEM,
                "Run started. Scenario=" + scenarioType);
        runUntilPaused(state);
        return state;
    }

    /** Human approves the current pending gate; engine resumes and runs until the next pause point. */
    public WorkflowState approveGate(WorkflowState state, String approvedBy, String notes) {
        if (state.getStatus() != RunStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Run " + state.getRunId() + " has no pending gate to approve (status="
                    + state.getStatus() + ")");
        }
        Stage gate = state.getPendingGateStage();
        auditLogger.logHumanDecision(state, gate, approvedBy, notes);
        state.setPendingGateStage(null);

        // Dynamic re-planning: if Architecture deferred design pending clarification
        // and the human supplied an answer, re-run ARCHITECTURE with that answer
        // instead of blindly advancing to IMPLEMENTATION with an empty design.
        if (gate == Stage.ARCHITECTURE && isDesignDeferredPendingClarification(state) && notes != null && !notes.isBlank()) {
            state.appendClarificationAnswer(notes);
            state.addDecision(Stage.ARCHITECTURE, Actor.SYSTEM,
                    "Clarification received - re-running ARCHITECTURE instead of advancing with an empty design.");
            state.setCurrentStage(Stage.ARCHITECTURE);
            state.setStatus(RunStatus.RUNNING);
            runUntilPaused(state);
            return state;
        }

        state.setCurrentStage(nextStageAfter(gate));
        state.setStatus(RunStatus.RUNNING);
        runUntilPaused(state);
        return state;
    }

    private boolean isDesignDeferredPendingClarification(WorkflowState state) {
        var design = state.getDesign();
        return design != null
                && (design.impactedFiles() == null || design.impactedFiles().isEmpty())
                && design.clarificationQuestions() != null
                && !design.clarificationQuestions().isEmpty();
    }

    /** Human resolves a guardrail block (e.g. fixed the flagged issue); engine resumes past it. */
    public WorkflowState resolveBlock(WorkflowState state, String resolvedBy, String notes) {
        if (state.getStatus() != RunStatus.BLOCKED) {
            throw new IllegalStateException("Run " + state.getRunId() + " is not blocked (status="
                    + state.getStatus() + ")");
        }
        Stage blocked = state.getBlockedStage();
        auditLogger.logHumanDecision(state, blocked, resolvedBy, "Block resolved: " + notes);

        state.setBlockedStage(null);
        state.setCurrentStage(nextStageAfter(blocked));
        state.setStatus(RunStatus.RUNNING);
        runUntilPaused(state);
        return state;
    }

    private void runUntilPaused(WorkflowState state) {
        while (state.getStatus() == RunStatus.RUNNING) {
            advanceOneStage(state);
        }
    }

    private void advanceOneStage(WorkflowState state) {
        Stage current = state.getCurrentStage();

        if (current.isTerminal()) {
            state.setStatus(current == Stage.COMPLETED ? RunStatus.COMPLETED : RunStatus.FAILED);
            return;
        }

        WorkflowNode node = nodes.get(current);
        if (node == null) {
            throw new IllegalStateException("No WorkflowNode registered for stage " + current);
        }

        long start = System.currentTimeMillis();
        StageResult result;
        try {
            result = node.execute(state);
        } catch (Exception ex) {
            result = new StageResult(NodeOutcome.FAILURE, "Unhandled exception in " + current + ": " + ex.getMessage());
        }
        long durationMs = System.currentTimeMillis() - start;
        auditLogger.logStageExecution(state, current, result, durationMs);

        switch (result.outcome()) {
            case SUCCESS -> handleSuccess(state, current);
            case FAILURE -> handleFailure(state, current);
            case BLOCKED -> handleBlocked(state, current);
        }
    }

    private void handleSuccess(WorkflowState state, Stage current) {
        if (current.isGate()) {
            state.setPendingGateStage(current);
            state.setStatus(RunStatus.PENDING_APPROVAL);
            return;
        }
        Stage next = nextStageAfter(current);
        state.setCurrentStage(next);
        if (next == Stage.COMPLETED) {
            state.setStatus(RunStatus.COMPLETED);
        }
    }

    private void handleFailure(WorkflowState state, Stage current) {
        if (current == Stage.TESTING || current == Stage.IMPLEMENTATION) {
            if (state.retriesFor(Stage.IMPLEMENTATION) >= MAX_IMPLEMENTATION_RETRIES) {
                state.addDecision(current, Actor.SYSTEM,
                        "Max retries (" + MAX_IMPLEMENTATION_RETRIES + ") exceeded - rolling back and stopping safely.");
                state.setCurrentStage(Stage.ROLLED_BACK);
                state.setStatus(RunStatus.FAILED);
                return;
            }
            int attempt = state.incrementRetry(Stage.IMPLEMENTATION);
            String reason = current == Stage.IMPLEMENTATION
                    ? "Implementation plan failed validation - retrying IMPLEMENTATION (attempt "
                    : "Tests failed - retrying IMPLEMENTATION (attempt ";
            state.addDecision(current, Actor.SYSTEM,
                    reason + attempt + " of " + MAX_IMPLEMENTATION_RETRIES + ")");
            state.setCurrentStage(Stage.IMPLEMENTATION);
            return; // stays RUNNING, loop continues
        }

        // Any other stage failing is a hard stop - no silent partial progress.
        state.setCurrentStage(Stage.FAILED);
        state.setStatus(RunStatus.FAILED);
    }

    private void handleBlocked(WorkflowState state, Stage current) {
        state.setBlockedStage(current);
        state.setStatus(RunStatus.BLOCKED);
    }

    private Stage nextStageAfter(Stage stage) {
        int idx = PIPELINE_ORDER.indexOf(stage);
        if (idx < 0 || idx == PIPELINE_ORDER.size() - 1) {
            return Stage.COMPLETED;
        }
        return PIPELINE_ORDER.get(idx + 1);
    }
}
