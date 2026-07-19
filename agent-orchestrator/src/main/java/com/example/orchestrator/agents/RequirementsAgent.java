package com.example.orchestrator.agents;

import com.example.orchestrator.domain.RequirementSpec;
import com.example.orchestrator.graph.NodeOutcome;
import com.example.orchestrator.graph.StageResult;
import com.example.orchestrator.graph.WorkflowNode;
import com.example.orchestrator.llm.AgentLlmClient;
import com.example.orchestrator.llm.JsonExtractionUtil;
import com.example.orchestrator.state.Actor;
import com.example.orchestrator.state.ScenarioType;
import com.example.orchestrator.state.Stage;
import com.example.orchestrator.state.WorkflowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class RequirementsAgent implements WorkflowNode {

    private static final String SYSTEM_PROMPT = """
            You are a senior requirements analyst on a software engineering
            team. Given a raw feature request, produce the structured
            specification that every downstream agent (architecture,
            implementation, testing) will treat as the contract for this run.

            Separate FUNCTIONAL requirements (specific capabilities the
            system must do - e.g. "expose POST /shorten that returns a short
            code") from NON-FUNCTIONAL requirements (constraints - e.g.
            "short codes must be unguessable", "redirect latency under
            200ms"). Each functional requirement should be concrete enough
            that another engineer could check whether it was actually built.

            If the requirement is genuinely ambiguous (the scenarioType will
            say AMBIGUOUS, but judge independently too - a GREENFIELD or
            BROWNFIELD request can still contain ambiguity), do NOT silently
            resolve it with an assumption. Put it in "clarificationQuestions"
            as a specific, answerable question instead. Only use
            "assumptions" for genuinely minor defaults a senior engineer
            would reasonably make without asking (e.g. "no auth changes in
            scope unless stated").

            Respond with ONLY valid JSON, no markdown fences, no commentary,
            matching exactly:
            {
              "functionalRequirements": ["specific, checkable capability"],
              "nonFunctionalRequirements": ["specific constraint"],
              "acceptanceCriteria": ["specific, testable criterion"],
              "ambiguities": ["a genuinely underspecified aspect, described"],
              "clarificationQuestions": ["a specific question a human should answer before design proceeds"],
              "assumptions": ["a minor default assumption, if any"]
            }
            """;

    private final AgentLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public RequirementsAgent(AgentLlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Stage stage() {
        return Stage.REQUIREMENTS;
    }

    @Override
    public StageResult execute(WorkflowState state) {
        String userPrompt = """
                SCHEMA_ID: REQUIREMENTS_SPEC_V1
                Scenario type: %s
                Raw requirement:
                %s
                """.formatted(state.getScenarioType(), state.getRawRequirement());

        String raw = llmClient.complete(SYSTEM_PROMPT, userPrompt);

        try {
            RequirementSpec spec = objectMapper.readValue(
                    JsonExtractionUtil.extractJson(raw), RequirementSpec.class);
            state.setSpec(spec);

            boolean hasOpenQuestions = spec.clarificationQuestions() != null && !spec.clarificationQuestions().isEmpty();
            String summary = "Generated spec: %d functional, %d non-functional requirements, %d clarification question(s)%s."
                    .formatted(
                            sizeOf(spec.functionalRequirements()),
                            sizeOf(spec.nonFunctionalRequirements()),
                            sizeOf(spec.clarificationQuestions()),
                            state.getScenarioType() == ScenarioType.AMBIGUOUS && hasOpenQuestions
                                    ? " - review these before approving the gate" : "");
            state.addDecision(Stage.REQUIREMENTS, Actor.AGENT, summary);
            return new StageResult(NodeOutcome.SUCCESS, "Requirement spec generated - awaiting human approval gate.");
        } catch (Exception e) {
            state.addDecision(Stage.REQUIREMENTS, Actor.SYSTEM,
                    "Failed to parse requirement spec JSON: " + e.getMessage());
            return new StageResult(NodeOutcome.FAILURE, "Could not parse LLM output as valid RequirementSpec JSON.");
        }
    }

    private int sizeOf(java.util.List<String> list) {
        return list == null ? 0 : list.size();
    }
}
