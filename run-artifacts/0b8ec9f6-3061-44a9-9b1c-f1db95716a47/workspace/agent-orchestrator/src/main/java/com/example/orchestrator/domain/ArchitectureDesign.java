package com.example.orchestrator.domain;

import java.util.List;

public record ArchitectureDesign(
        List<ImpactedFile> impactedFiles,
        List<String> designDecisions,
        List<String> apiEndpoints,
        List<String> clarificationQuestions,
        String notes
) {
    /**
     * changeType is "CREATE" (file does not exist yet) or "MODIFY" (file
     * exists and must be edited, not regenerated). ImplementationAgent
     * re-verifies this against the actual workspace before acting on it -
     * this field is a strong hint from Architecture, not a blind order.
     */
    public record ImpactedFile(String path, String changeType, String reason) {
    }
}
