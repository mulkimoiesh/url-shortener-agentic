package com.example.orchestrator.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record ResolveBlockRequest(
        @NotBlank String resolvedBy,
        @NotBlank String notes
) {
}
