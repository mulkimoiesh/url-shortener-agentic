package com.example.orchestrator.controller.dto;

import com.example.orchestrator.domain.*;
import com.example.orchestrator.state.DecisionRecord;
import com.example.orchestrator.state.ScenarioType;
import com.example.orchestrator.state.Stage;
import com.example.orchestrator.state.WorkflowState;

import java.time.Instant;
import java.util.List;

/** Read model returned by the API - flattens WorkflowState for JSON serialization. */
public record RunView(
        String runId,
        String rawRequirement,
        ScenarioType scenarioType,
        Stage currentStage,
        String status,
        Stage pendingGateStage,
        Stage blockedStage,
        RequirementSpec spec,
        ArchitectureDesign design,
        ImplementationResult implementation,
        TestResult testResult,
        GuardrailResult guardrailResult,
        DocsResult docsResult,
        ReleaseChecklist releaseChecklist,
        List<DecisionRecord> decisionLog,
        Instant startedAt,
        Instant updatedAt
) {
    public static RunView from(WorkflowState s) {
        return new RunView(
                s.getRunId(), s.getRawRequirement(), s.getScenarioType(), s.getCurrentStage(),
                s.getStatus().name(), s.getPendingGateStage(), s.getBlockedStage(),
                s.getSpec(), s.getDesign(), s.getImplementation(), s.getTestResult(),
                s.getGuardrailResult(), s.getDocsResult(), s.getReleaseChecklist(),
                s.getDecisionLog(), s.getStartedAt(), s.getUpdatedAt()
        );
    }
}
