package com.example.orchestrator.graph;

import com.example.orchestrator.state.Stage;
import com.example.orchestrator.state.WorkflowState;

/**
 * One executable node in the SDLC graph. Implementations mutate the shared
 * WorkflowState (writing their stage's domain output + decision log entries)
 * and return a StageResult telling the engine how to route next.
 */
public interface WorkflowNode {
    Stage stage();
    StageResult execute(WorkflowState state);
}
