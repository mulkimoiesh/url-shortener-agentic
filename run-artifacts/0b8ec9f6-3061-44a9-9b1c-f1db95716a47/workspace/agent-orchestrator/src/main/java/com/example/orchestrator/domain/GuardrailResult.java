package com.example.orchestrator.domain;

import java.util.List;

/** Filled properly in Stage 3. */
public record GuardrailResult(
        boolean passed,
        List<String> violations,
        String notes
) {
}
