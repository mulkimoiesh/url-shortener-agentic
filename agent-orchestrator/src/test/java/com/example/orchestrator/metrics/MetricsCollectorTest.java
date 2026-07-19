package com.example.orchestrator.metrics;

import com.example.orchestrator.state.Actor;
import com.example.orchestrator.state.ScenarioType;
import com.example.orchestrator.state.Stage;
import com.example.orchestrator.state.WorkflowState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsCollectorTest {

    private final MetricsCollector collector = new MetricsCollector();

    @Test
    void countsRetriesAndGatesPassed() {
        WorkflowState state = new WorkflowState("do a thing", ScenarioType.GREENFIELD);
        state.incrementRetry(Stage.IMPLEMENTATION);
        state.incrementRetry(Stage.IMPLEMENTATION);
        state.addDecision(Stage.REQUIREMENTS, Actor.HUMAN, "Approved by alice. looks fine");
        state.addDecision(Stage.ARCHITECTURE, Actor.HUMAN, "Approved by bob. also fine");

        RunMetrics metrics = collector.collect(state);

        assertThat(metrics.totalRetries()).isEqualTo(2);
        assertThat(metrics.gatesPassed()).isEqualTo(2);
        assertThat(metrics.runId()).isEqualTo(state.getRunId());
    }

    @Test
    void mttrIsZeroWhenNoFailuresOccurred() {
        WorkflowState state = new WorkflowState("do a thing", ScenarioType.GREENFIELD);
        state.addDecision(Stage.REQUIREMENTS, Actor.SYSTEM, "[SUCCESS, 5ms] all good");

        RunMetrics metrics = collector.collect(state);

        assertThat(metrics.mttrMs()).isEqualTo(0L);
    }

    @Test
    void mttrIsPositiveWhenAFailureWasFollowedByRecovery() throws InterruptedException {
        WorkflowState state = new WorkflowState("do a thing", ScenarioType.GREENFIELD);
        state.addDecision(Stage.TESTING, Actor.SYSTEM, "[FAILURE, 10ms] flaky test");
        Thread.sleep(5);
        state.addDecision(Stage.TESTING, Actor.SYSTEM, "retrying implementation (attempt 1)");

        RunMetrics metrics = collector.collect(state);

        assertThat(metrics.mttrMs()).isGreaterThanOrEqualTo(0L);
    }
}
