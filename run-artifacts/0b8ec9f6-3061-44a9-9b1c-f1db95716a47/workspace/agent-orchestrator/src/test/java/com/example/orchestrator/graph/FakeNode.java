package com.example.orchestrator.graph;

import com.example.orchestrator.state.Stage;
import com.example.orchestrator.state.WorkflowState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A WorkflowNode whose outcomes are scripted in advance, so WorkflowEngine's
 * routing logic (gates, retry, rollback, block) can be tested without any
 * LLM/network/filesystem dependency. Once the scripted queue is exhausted,
 * it keeps returning the last outcome.
 */
class FakeNode implements WorkflowNode {

    private final Stage stage;
    private final Deque<StageResult> scriptedResults;
    private StageResult lastResult;
    private int executionCount = 0;

    FakeNode(Stage stage, StageResult... results) {
        this.stage = stage;
        this.scriptedResults = new ArrayDeque<>(List.of(results));
    }

    static FakeNode alwaysSucceeds(Stage stage) {
        return new FakeNode(stage, new StageResult(NodeOutcome.SUCCESS, "fake success"));
    }

    static FakeNode alwaysFails(Stage stage) {
        return new FakeNode(stage, new StageResult(NodeOutcome.FAILURE, "fake failure"));
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public StageResult execute(WorkflowState state) {
        executionCount++;
        lastResult = scriptedResults.isEmpty() ? lastResult : scriptedResults.poll();
        return lastResult;
    }

    int executionCount() {
        return executionCount;
    }
}
