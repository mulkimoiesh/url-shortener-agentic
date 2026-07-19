package com.example.orchestrator.metrics;

import java.util.Map;

public record RunMetrics(
        String runId,
        String status,
        boolean success,
        long totalDurationMs,
        int totalRetries,
        int gatesPassed,
        long mttrMs,
        Map<String, Long> stageDurationsMs
) {
}
