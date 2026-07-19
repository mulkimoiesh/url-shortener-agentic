package com.example.orchestrator.controller.dto;

import com.example.orchestrator.state.ScenarioType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StartRunRequest(
        @NotBlank String rawRequirement,
        @NotNull ScenarioType scenarioType
) {
}
