package com.example.orchestrator.agents;

import com.example.orchestrator.codebase.WorkspaceService;
import com.example.orchestrator.domain.ArchitectureDesign;
import com.example.orchestrator.domain.ImplementationPlan;
import com.example.orchestrator.domain.ImplementationResult;
import com.example.orchestrator.graph.NodeOutcome;
import com.example.orchestrator.graph.StageResult;
import com.example.orchestrator.graph.WorkflowNode;
import com.example.orchestrator.implementation.*;
import com.example.orchestrator.llm.AgentLlmClient;
import com.example.orchestrator.llm.JsonExtractionUtil;
import com.example.orchestrator.state.Actor;
import com.example.orchestrator.state.Stage;
import com.example.orchestrator.state.WorkflowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic orchestrator, modeled on how Cursor/Claude Code/Windsurf
 * structure this: the LLM only ever generates code; every CREATE-vs-MODIFY
 * decision, every "does this class already exist under another name"
 * question, and every correctness check happens in plain Java, before and
 * after the single LLM call. See the implementation/ package for each step:
 *
 *  STEP 1  ProjectIndexer   - index the workspace
 *  STEP 2  ChangePlanner    - decide CREATE/MODIFY/SKIP per file
 *  STEP 2b TestImpactAnalyzer - discover existing tests broken by a MODIFY target, so
 *          they're included as MODIFY targets too instead of being silently left broken
 *  STEP 3+5 ImplementationPromptBuilder - concise prompt, full content only for MODIFY targets
 *  STEP 6  (this class)     - call the LLM
 *  STEP 7  ImplementationValidator - validate before writing anything
 *  STEP 8  (this class)     - write into the workspace only, never ProductRoot
 *  STEP 9/10 handled by TestAgent + WorkflowEngine's bounded IMPLEMENTATION
 *            retry loop (compiler/test errors flow back via
 *            WorkflowState.lastImplementationFailureNotes / getTestResult())
 */
@Service
public class ImplementationAgent implements WorkflowNode {

    private final AgentLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspace;
    private final ProjectIndexer projectIndexer;
    private final ChangePlanner changePlanner;
    private final ImplementationPromptBuilder promptBuilder;
    private final ImplementationValidator validator;
    private final TestImpactAnalyzer testImpactAnalyzer;

    public ImplementationAgent(AgentLlmClient llmClient, ObjectMapper objectMapper, WorkspaceService workspace,
                                ProjectIndexer projectIndexer, ChangePlanner changePlanner,
                                ImplementationPromptBuilder promptBuilder, ImplementationValidator validator,
                                TestImpactAnalyzer testImpactAnalyzer) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.workspace = workspace;
        this.projectIndexer = projectIndexer;
        this.changePlanner = changePlanner;
        this.promptBuilder = promptBuilder;
        this.validator = validator;
        this.testImpactAnalyzer = testImpactAnalyzer;
    }

    @Override
    public Stage stage() {
        return Stage.IMPLEMENTATION;
    }

    @Override
    public StageResult execute(WorkflowState state) {
        String runId = state.getRunId();
        workspace.ensureWorkspace(runId);

        ArchitectureDesign design = state.getDesign();
        if (design == null || design.impactedFiles() == null || design.impactedFiles().isEmpty()) {
            state.setImplementation(new ImplementationResult(List.of(),
                    "No implementation attempted - architecture has not produced a concrete design yet."));
            state.addDecision(Stage.IMPLEMENTATION, Actor.SYSTEM, "Skipped - no impacted files in the current design.");
            return new StageResult(NodeOutcome.SUCCESS, "No-op: nothing to implement yet.");
        }

        // STEP 1
        ProjectIndex index = projectIndexer.index(runId);

        // STEP 2 - CREATE/MODIFY/SKIP decided here, deterministically, not by the LLM
        List<ChangeDecision> decisions = changePlanner.plan(index, design);
        logPlanningDecisions(state, decisions);

        if (decisions.stream().allMatch(d -> d.changeType() == ChangeType.SKIP)) {
            state.setImplementation(new ImplementationResult(List.of(),
                    "Every impacted file was already satisfied - no changes needed."));
            state.addDecision(Stage.IMPLEMENTATION, Actor.AGENT, "All targets SKIPped - feature already present.");
            return new StageResult(NodeOutcome.SUCCESS, "No-op: all impacted files already satisfy the requirement.");
        }

        // STEP 2b - discover existing tests impacted by the production MODIFY targets above,
        // so this run can keep them compiling/passing instead of leaving them silently broken
        // (Implementation otherwise has zero visibility into src/test/**).
        List<ChangeDecision> impactedTestDecisions = discoverImpactedTestDecisions(runId, index, decisions);
        logTestImpactDecisions(state, impactedTestDecisions);

        int attempt = state.retriesFor(Stage.IMPLEMENTATION);
        String priorFailureContext = buildPriorFailureContext(state, attempt);

        // STEP 3 + 5
        String userPrompt = promptBuilder.buildUserPrompt(runId, index, design, decisions, impactedTestDecisions,
                state.getSpec() != null ? state.getSpec().acceptanceCriteria() : List.of(),
                priorFailureContext, state.getRawRequirement());

        // STEP 6
        String raw = llmClient.complete(ImplementationPromptBuilder.SYSTEM_PROMPT, userPrompt);

        ImplementationPlan plan;
        try {
            plan = objectMapper.readValue(JsonExtractionUtil.extractJson(raw), ImplementationPlan.class);
        } catch (Exception e) {
            return fail(state, attempt, "Failed to parse implementation plan JSON: " + e.getMessage());
        }

        if (plan.files() == null || plan.files().isEmpty()) {
            state.setImplementation(new ImplementationResult(List.of(), plan.notes()));
            state.setLastImplementationFailureNotes(null);
            state.addDecision(Stage.IMPLEMENTATION, Actor.AGENT, "No file changes proposed: " + plan.notes());
            return new StageResult(NodeOutcome.SUCCESS, "No changes needed - " + plan.notes());
        }

        // STEP 7 - validate everything before writing anything (production decisions plus the
        // discovered test decisions, so a test the model touches is validated as a planned MODIFY
        // rather than rejected as an unplanned file it "shouldn't" have known about)
        List<ChangeDecision> allDecisions = new ArrayList<>(decisions);
        allDecisions.addAll(impactedTestDecisions);
        List<String> violations = validator.validate(runId, index, allDecisions, plan);
        if (!violations.isEmpty()) {
            return fail(state, attempt, "Validation rejected the plan: " + violations);
        }

        // STEP 8 - write into the workspace only
        List<String> written = plan.files().stream()
                .peek(f -> workspace.writeProductFile(runId, f.path(), f.content()))
                .map(ImplementationPlan.FileChange::path)
                .toList();

        state.setImplementation(new ImplementationResult(written, plan.notes()));
        state.setLastImplementationFailureNotes(null);
        state.addDecision(Stage.IMPLEMENTATION, Actor.AGENT,
                "Wrote %d file(s) to workspace (attempt %d): %s".formatted(written.size(), attempt, written));
        return new StageResult(NodeOutcome.SUCCESS, "%d file(s) written to workspace.".formatted(written.size()));
    }

    private void logPlanningDecisions(WorkflowState state, List<ChangeDecision> decisions) {
        long creates = decisions.stream().filter(d -> d.changeType() == ChangeType.CREATE).count();
        long modifies = decisions.stream().filter(d -> d.changeType() == ChangeType.MODIFY).count();
        long skips = decisions.stream().filter(d -> d.changeType() == ChangeType.SKIP).count();
        long redirected = decisions.stream()
                .filter(d -> d.changeType() == ChangeType.MODIFY && !d.resolvedPath().equals(d.architecturePath()))
                .count();

        state.addDecision(Stage.IMPLEMENTATION, Actor.SYSTEM,
                "ChangePlanner decided (deterministically, before any LLM call): %d CREATE, %d MODIFY (%d redirected to an existing equivalent class), %d SKIP."
                        .formatted(creates, modifies, redirected, skips));
    }

    /**
     * Only MODIFY targets can break an existing test - a CREATE is a brand-new file nothing
     * else could reference yet. For each one, resolve its simple class name and ask
     * TestImpactAnalyzer which existing test files reference it (by naming convention or by
     * content), deduplicating across multiple production decisions that touch a shared test.
     */
    private List<ChangeDecision> discoverImpactedTestDecisions(String runId, ProjectIndex index,
                                                                 List<ChangeDecision> decisions) {
        List<ChangeDecision> testDecisions = new ArrayList<>();
        Set<String> seenTestPaths = new LinkedHashSet<>();
        for (ChangeDecision d : decisions) {
            if (d.changeType() != ChangeType.MODIFY) {
                continue;
            }
            String simpleName = index.findByPath(d.resolvedPath())
                    .map(ClassInfo::simpleName)
                    .orElseGet(() -> simpleNameFromPath(d.resolvedPath()));
            if (simpleName == null) {
                continue;
            }
            for (String testPath : testImpactAnalyzer.findImpactedTests(runId, simpleName)) {
                if (seenTestPaths.add(testPath)) {
                    testDecisions.add(new ChangeDecision(testPath, testPath, ChangeType.MODIFY, simpleName,
                            "Existing test references '" + simpleName + "', modified by this plan - "
                                    + "update only what's needed to keep it compiling and passing."));
                }
            }
        }
        return testDecisions;
    }

    private void logTestImpactDecisions(WorkflowState state, List<ChangeDecision> testDecisions) {
        if (testDecisions.isEmpty()) {
            return;
        }
        state.addDecision(Stage.IMPLEMENTATION, Actor.SYSTEM,
                "Discovered %d existing test file(s) referencing modified classes - included for update: %s"
                        .formatted(testDecisions.size(), testDecisions.stream().map(ChangeDecision::resolvedPath).toList()));
    }

    private String simpleNameFromPath(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    private StageResult fail(WorkflowState state, int attempt, String message) {
        String msg = message + " (attempt " + attempt + ")";
        state.setLastImplementationFailureNotes(msg);
        state.addDecision(Stage.IMPLEMENTATION, Actor.SYSTEM, msg);
        return new StageResult(NodeOutcome.FAILURE, msg);
    }

    private String buildPriorFailureContext(WorkflowState state, int attempt) {
        if (attempt == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("A PREVIOUS ATTEMPT FAILED - fix this without discarding what already works:\n");
        if (state.getLastImplementationFailureNotes() != null) {
            sb.append(state.getLastImplementationFailureNotes()).append("\n");
        }
        if (state.getTestResult() != null && !state.getTestResult().passed()) {
            sb.append("Compiler/test output:\n").append(state.getTestResult().notes()).append("\n");
        }
        return sb.toString();
    }
}
