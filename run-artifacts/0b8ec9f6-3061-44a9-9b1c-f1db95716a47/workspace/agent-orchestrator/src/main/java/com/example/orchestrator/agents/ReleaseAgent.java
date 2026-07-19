package com.example.orchestrator.agents;

import com.example.orchestrator.domain.ReleaseChecklist;
import com.example.orchestrator.graph.NodeOutcome;
import com.example.orchestrator.graph.StageResult;
import com.example.orchestrator.graph.WorkflowNode;
import com.example.orchestrator.state.Actor;
import com.example.orchestrator.state.Stage;
import com.example.orchestrator.state.WorkflowState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic checklist from the run's actual recorded outcomes - not an
 * LLM opinion on whether things "seem fine". This stage is itself a gate
 * (Stage.RELEASE.isGate() == true), so even an all-green checklist still
 * stops for a human's final sign-off before COMPLETED.
 */
@Service
public class ReleaseAgent implements WorkflowNode {

    @Override
    public Stage stage() {
        return Stage.RELEASE;
    }

    @Override
    public StageResult execute(WorkflowState state) {
        List<String> items = new ArrayList<>();
        boolean testsPassed = state.getTestResult() != null && state.getTestResult().passed();
        boolean guardrailsPassed = state.getGuardrailResult() != null && state.getGuardrailResult().passed();
        boolean docsGenerated = state.getDocsResult() != null;
        int retries = state.retriesFor(Stage.IMPLEMENTATION);

        items.add((testsPassed ? "[PASS] " : "[FAIL] ") + "Test suite passing (includes compile check)");
        items.add((guardrailsPassed ? "[PASS] " : "[FAIL] ") + "No guardrail violations (secrets, duplicates, package integrity)");
        items.add((docsGenerated ? "[PASS] " : "[FAIL] ") + "Run documentation generated");
        items.add("[INFO] Required API endpoints per architecture: "
                + (state.getDesign() != null ? sizeOf(state.getDesign().apiEndpoints()) : 0)
                + " (functional completeness enforced during IMPLEMENTATION - see decision log)");
        items.add("[INFO] Implementation retries used: " + retries);
        items.add("[INFO] Ambiguities flagged at requirements stage: "
                + (state.getSpec() != null ? state.getSpec().ambiguities().size() : "unknown"));

        boolean approved = testsPassed && guardrailsPassed && docsGenerated;
        ReleaseChecklist checklist = new ReleaseChecklist(approved, items,
                approved ? "All automated checks passed - awaiting final human sign-off."
                         : "One or more automated checks failed - review before approving.");
        state.setReleaseChecklist(checklist);
        state.addDecision(Stage.RELEASE, Actor.AGENT, checklist.notes());

        // Always SUCCESS here (the checklist itself may say "not ready") -
        // the human gate that follows is where a real no-go actually stops the release.
        return new StageResult(NodeOutcome.SUCCESS, checklist.notes());
    }

    private int sizeOf(java.util.List<String> list) {
        return list == null ? 0 : list.size();
    }
}
