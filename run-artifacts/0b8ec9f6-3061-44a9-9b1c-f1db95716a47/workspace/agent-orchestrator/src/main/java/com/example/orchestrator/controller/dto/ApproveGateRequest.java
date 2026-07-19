package com.example.orchestrator.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record ApproveGateRequest(
        @NotBlank String approvedBy,
        String notes
) {
}
