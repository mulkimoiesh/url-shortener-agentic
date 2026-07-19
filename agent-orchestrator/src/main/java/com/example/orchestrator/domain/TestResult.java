package com.example.orchestrator.domain;

/** Filled properly in Stage 3 - will run real ./gradlew test and parse output. */
public record TestResult(
        boolean passed,
        int testsRun,
        int testsFailed,
        String notes
) {
}
