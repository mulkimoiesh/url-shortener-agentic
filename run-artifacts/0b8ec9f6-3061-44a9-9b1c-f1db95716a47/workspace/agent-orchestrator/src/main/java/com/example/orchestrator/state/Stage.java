package com.example.orchestrator.state;

/**
 * The stages of the SDLC graph. Order here is the "happy path" order used by
 * WorkflowEngine.nextStageAfter(), but transitions are NOT purely linear:
 * see WorkflowEngine for the retry-loop (TESTING -> IMPLEMENTATION) and
 * block edge (GUARDRAILS -> BLOCKED) that make this a graph, not a chain.
 */
public enum Stage {
    REQUIREMENTS,
    ARCHITECTURE,
    IMPLEMENTATION,
    TESTING,
    GUARDRAILS,
    DOCUMENTATION,
    RELEASE,
    COMPLETED,
    FAILED,
    ROLLED_BACK;

    /** Stages that require an explicit human approval before the graph proceeds. */
    public boolean isGate() {
        return this == REQUIREMENTS || this == ARCHITECTURE || this == RELEASE;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == ROLLED_BACK;
    }
}
