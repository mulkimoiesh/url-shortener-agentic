package com.example.orchestrator.state;

import java.time.Instant;

public record DecisionRecord(
        Instant timestamp,
        Stage stage,
        Actor actor,
        String summary
) {
}
