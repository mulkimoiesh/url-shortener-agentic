package com.example.orchestrator.domain;

import java.util.List;

/** Filled properly in Stage 3. */
public record ImplementationResult(
        List<String> filesChanged,
        String notes
) {
}
