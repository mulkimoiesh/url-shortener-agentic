package com.example.orchestrator.state;

import com.example.orchestrator.domain.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Carries requirement -> release lineage for a single orchestration run.
 * This is the "preserve cross-stage context and decision lineage" object:
 * every agent reads from and writes to the same instance, and every
 * transition is appended to decisionLog rather than overwriting history.
 */
public class WorkflowState {

    private final String runId;
    private final String rawRequirement;
    private final ScenarioType scenarioType;
    private final Instant startedAt;

    private Stage currentStage;
    private RunStatus status;
    private Stage pendingGateStage;   // set while status == PENDING_APPROVAL
    private Stage blockedStage;       // set while status == BLOCKED

    private RequirementSpec spec;
    private ArchitectureDesign design;
    private ImplementationResult implementation;
    private TestResult testResult;
    private GuardrailResult guardrailResult;
    private DocsResult docsResult;
    private ReleaseChecklist releaseChecklist;
    private String lastImplementationFailureNotes;

    private final List<DecisionRecord> decisionLog = new ArrayList<>();
    private final List<String> clarificationAnswers = new ArrayList<>();
    private final Map<Stage, Integer> retryCounts = new HashMap<>();
    private final Map<Stage, Long> stageDurationsMs = new HashMap<>();
    private Instant updatedAt;

    public WorkflowState(String rawRequirement, ScenarioType scenarioType) {
        this.runId = UUID.randomUUID().toString();
        this.rawRequirement = rawRequirement;
        this.scenarioType = scenarioType;
        this.startedAt = Instant.now();
        this.updatedAt = this.startedAt;
        this.currentStage = Stage.REQUIREMENTS;
        this.status = RunStatus.RUNNING;
    }

    public void addDecision(Stage stage, Actor actor, String summary) {
        decisionLog.add(new DecisionRecord(Instant.now(), stage, actor, summary));
        touch();
    }

    public void appendClarificationAnswer(String answer) {
        clarificationAnswers.add(answer);
        touch();
    }

    public List<String> getClarificationAnswers() {
        return List.copyOf(clarificationAnswers);
    }

    public int incrementRetry(Stage stage) {
        int next = retryCounts.merge(stage, 1, Integer::sum);
        touch();
        return next;
    }

    public int retriesFor(Stage stage) {
        return retryCounts.getOrDefault(stage, 0);
    }

    public void recordStageDuration(Stage stage, long durationMs) {
        stageDurationsMs.merge(stage, durationMs, Long::sum);
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    // --- getters / setters ---

    public String getRunId() { return runId; }
    public String getRawRequirement() { return rawRequirement; }
    public ScenarioType getScenarioType() { return scenarioType; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public Stage getCurrentStage() { return currentStage; }
    public void setCurrentStage(Stage currentStage) { this.currentStage = currentStage; touch(); }

    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; touch(); }

    public Stage getPendingGateStage() { return pendingGateStage; }
    public void setPendingGateStage(Stage pendingGateStage) { this.pendingGateStage = pendingGateStage; }

    public Stage getBlockedStage() { return blockedStage; }
    public void setBlockedStage(Stage blockedStage) { this.blockedStage = blockedStage; }

    public RequirementSpec getSpec() { return spec; }
    public void setSpec(RequirementSpec spec) { this.spec = spec; touch(); }

    public ArchitectureDesign getDesign() { return design; }
    public void setDesign(ArchitectureDesign design) { this.design = design; touch(); }

    public ImplementationResult getImplementation() { return implementation; }
    public void setImplementation(ImplementationResult implementation) { this.implementation = implementation; touch(); }

    public TestResult getTestResult() { return testResult; }
    public void setTestResult(TestResult testResult) { this.testResult = testResult; touch(); }

    public GuardrailResult getGuardrailResult() { return guardrailResult; }
    public void setGuardrailResult(GuardrailResult guardrailResult) { this.guardrailResult = guardrailResult; touch(); }

    public DocsResult getDocsResult() { return docsResult; }
    public void setDocsResult(DocsResult docsResult) { this.docsResult = docsResult; touch(); }

    public ReleaseChecklist getReleaseChecklist() { return releaseChecklist; }
    public void setReleaseChecklist(ReleaseChecklist releaseChecklist) { this.releaseChecklist = releaseChecklist; touch(); }

    public String getLastImplementationFailureNotes() { return lastImplementationFailureNotes; }
    public void setLastImplementationFailureNotes(String notes) { this.lastImplementationFailureNotes = notes; touch(); }

    public List<DecisionRecord> getDecisionLog() { return List.copyOf(decisionLog); }
    public Map<Stage, Integer> getRetryCounts() { return Map.copyOf(retryCounts); }
    public Map<Stage, Long> getStageDurationsMs() { return Map.copyOf(stageDurationsMs); }
}
