package com.example.orchestrator.metrics;

import com.example.orchestrator.state.DecisionRecord;
import com.example.orchestrator.state.RunStatus;
import com.example.orchestrator.state.Stage;
import com.example.orchestrator.state.WorkflowState;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class MetricsCollector {

    public RunMetrics collect(WorkflowState state) {
        long totalDurationMs = Duration.between(state.getStartedAt(), state.getUpdatedAt()).toMillis();
        int totalRetries = state.getRetryCounts().values().stream().mapToInt(Integer::intValue).sum();

        long gatesPassed = state.getDecisionLog().stream()
                .filter(d -> d.actor() == com.example.orchestrator.state.Actor.HUMAN)
                .filter(d -> d.stage().isGate())
                .filter(d -> d.summary().startsWith("Approved by"))
                .count();

        long mttrMs = computeMttr(state.getDecisionLog());

        return new RunMetrics(
                state.getRunId(),
                state.getStatus().name(),
                state.getStatus() == RunStatus.COMPLETED,
                totalDurationMs,
                totalRetries,
                (int) gatesPassed,
                mttrMs,
                toStringKeyedMap(state.getStageDurationsMs())
        );
    }

    /**
     * Mean time to recovery: average elapsed time between a failure/block being
     * logged and the very next decision log entry (retry kicking in, or a human
     * resolving a block). Zero if the run never failed anything.
     */
    private long computeMttr(List<DecisionRecord> log) {
        List<Long> recoveryTimesMs = new java.util.ArrayList<>();
        for (int i = 0; i < log.size() - 1; i++) {
            String summary = log.get(i).summary();
            boolean isFailureEvent = summary.startsWith("[FAILURE") || summary.startsWith("[BLOCKED");
            if (isFailureEvent) {
                Instant failedAt = log.get(i).timestamp();
                Instant recoveredAt = log.get(i + 1).timestamp();
                recoveryTimesMs.add(Duration.between(failedAt, recoveredAt).toMillis());
            }
        }
        if (recoveryTimesMs.isEmpty()) {
            return 0L;
        }
        return (long) recoveryTimesMs.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private Map<String, Long> toStringKeyedMap(Map<Stage, Long> src) {
        return src.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
    }
}
