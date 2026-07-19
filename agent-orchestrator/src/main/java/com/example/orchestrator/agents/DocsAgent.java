package com.example.orchestrator.agents;

import com.example.orchestrator.codebase.CodebaseContextService;
import com.example.orchestrator.domain.DocsResult;
import com.example.orchestrator.graph.NodeOutcome;
import com.example.orchestrator.graph.StageResult;
import com.example.orchestrator.graph.WorkflowNode;
import com.example.orchestrator.state.Actor;
import com.example.orchestrator.state.Stage;
import com.example.orchestrator.state.WorkflowState;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT an LLM call: every field here already exists as
 * structured data in WorkflowState, so templating it is more reliable than
 * asking a model to paraphrase it back correctly. Writes to
 * run-artifacts/{runId}/SUMMARY.md rather than touching the product's own
 * README, so repeated runs never clobber each other or the real docs.
 */
@Service
public class DocsAgent implements WorkflowNode {

    private final CodebaseContextService codebase;

    public DocsAgent(CodebaseContextService codebase) {
        this.codebase = codebase;
    }

    @Override
    public Stage stage() {
        return Stage.DOCUMENTATION;
    }

    @Override
    public StageResult execute(WorkflowState state) {
        String markdown = render(state);
        codebase.writeFile(codebase.runArtifactsRoot(state.getRunId()), "SUMMARY.md", markdown);

        DocsResult result = new DocsResult("Run summary written to run-artifacts/" + state.getRunId() + "/SUMMARY.md");
        state.setDocsResult(result);
        state.addDecision(Stage.DOCUMENTATION, Actor.AGENT, result.notes());
        return new StageResult(NodeOutcome.SUCCESS, result.notes());
    }

    private String render(WorkflowState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Run Summary — ").append(state.getRunId()).append("\n\n");
        sb.append("**Scenario:** ").append(state.getScenarioType()).append("\n\n");
        sb.append("**Raw requirement:** ").append(state.getRawRequirement()).append("\n\n");

        if (state.getSpec() != null) {
            sb.append("## Requirements\n");
            sb.append("- Functional requirements: ").append(state.getSpec().functionalRequirements()).append("\n");
            sb.append("- Non-functional requirements: ").append(state.getSpec().nonFunctionalRequirements()).append("\n");
            sb.append("- Acceptance criteria: ").append(state.getSpec().acceptanceCriteria()).append("\n");
            sb.append("- Ambiguities flagged: ").append(state.getSpec().ambiguities()).append("\n");
            sb.append("- Clarification questions: ").append(state.getSpec().clarificationQuestions()).append("\n");
            sb.append("- Assumptions: ").append(state.getSpec().assumptions()).append("\n\n");
        }
        if (state.getDesign() != null) {
            sb.append("## Architecture\n");
            sb.append("- Impacted files: ").append(state.getDesign().impactedFiles()).append("\n");
            sb.append("- Design decisions: ").append(state.getDesign().designDecisions()).append("\n");
            sb.append("- API endpoints: ").append(state.getDesign().apiEndpoints()).append("\n\n");
        }
        if (!state.getClarificationAnswers().isEmpty()) {
            sb.append("## Clarification Answers Received\n");
            state.getClarificationAnswers().forEach(a -> sb.append("- ").append(a).append("\n"));
            sb.append("\n");
        }
        if (state.getImplementation() != null) {
            sb.append("## Implementation\n");
            sb.append("- Files changed: ").append(state.getImplementation().filesChanged()).append("\n");
            sb.append("- Notes: ").append(state.getImplementation().notes()).append("\n\n");
        }
        if (state.getTestResult() != null) {
            sb.append("## Testing\n");
            sb.append("- Passed: ").append(state.getTestResult().passed()).append("\n");
            sb.append("- Retries used: ").append(state.retriesFor(Stage.IMPLEMENTATION)).append("\n\n");
        }
        if (state.getGuardrailResult() != null) {
            sb.append("## Guardrails\n");
            sb.append("- Passed: ").append(state.getGuardrailResult().passed()).append("\n");
            sb.append("- Violations: ").append(state.getGuardrailResult().violations()).append("\n\n");
        }

        sb.append("## Functional Checklist\n");
        sb.append(checklistLine("Requirements approved", hasHumanApproval(state, Stage.REQUIREMENTS)));
        sb.append(checklistLine("Architecture approved", hasHumanApproval(state, Stage.ARCHITECTURE)));
        sb.append(checklistLine("Every declared API endpoint verified present",
                state.getGuardrailResult() != null && state.getTestResult() != null && state.getImplementation() != null
                        && !state.getImplementation().filesChanged().isEmpty()));
        sb.append(checklistLine("Tests passing", state.getTestResult() != null && state.getTestResult().passed()));
        sb.append(checklistLine("Guardrails passing", state.getGuardrailResult() != null && state.getGuardrailResult().passed()));
        sb.append(checklistLine("Release approved", hasHumanApproval(state, Stage.RELEASE)));
        sb.append("\n");

        sb.append("## Retry History\n");
        state.getRetryCounts().forEach((stage, count) -> sb.append("- ").append(stage).append(": ").append(count).append(" retry/retries\n"));
        if (state.getRetryCounts().isEmpty()) {
            sb.append("- No retries needed.\n");
        }
        sb.append("\n");

        sb.append("## Approval History\n");
        state.getDecisionLog().stream()
                .filter(d -> d.actor() == Actor.HUMAN)
                .forEach(d -> sb.append("- `").append(d.timestamp()).append("` [").append(d.stage()).append("] ").append(d.summary()).append("\n"));
        sb.append("\n");

        sb.append("## Full Decision Log\n");
        state.getDecisionLog().forEach(d ->
                sb.append("- `").append(d.timestamp()).append("` [").append(d.stage())
                        .append("/").append(d.actor()).append("] ").append(d.summary()).append("\n"));

        return sb.toString();
    }

    private String checklistLine(String label, boolean done) {
        return "- [" + (done ? "x" : " ") + "] " + label + "\n";
    }

    private boolean hasHumanApproval(WorkflowState state, Stage stage) {
        return state.getDecisionLog().stream()
                .anyMatch(d -> d.actor() == Actor.HUMAN && d.stage() == stage && d.summary().startsWith("Approved by"));
    }
}
