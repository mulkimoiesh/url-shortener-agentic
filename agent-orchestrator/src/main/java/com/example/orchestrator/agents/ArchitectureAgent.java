package com.example.orchestrator.agents;

import com.example.orchestrator.codebase.CodebaseContextService;
import com.example.orchestrator.domain.ArchitectureDesign;
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

import java.util.List;

/**
 * Three independent prompts, selected by ScenarioType, rather than one
 * generic prompt asked to "figure out" which mode it's in. This is the
 * fix for a subtler version of the original bug: a single shared prompt
 * tends to default toward "design a new app," even for brownfield asks,
 * because that's the more common pattern in an LLM's training data for
 * "design a system" requests. Splitting removes that bias structurally.
 */
@Service
public class ArchitectureAgent implements WorkflowNode {

    private static final String GREENFIELD_SYSTEM_PROMPT = """
            You are a senior software architect designing a NEW Spring Boot
            application from scratch, based on the approved requirement spec
            below. There is no existing codebase to consider for this design -
            do not reference or assume any pre-existing classes.
            Respond with ONLY valid JSON, no markdown fences, no commentary,
            matching exactly:
            {
              "impactedFiles": [
                {"path": "src/main/java/...", "changeType": "CREATE", "reason": "why this file is needed"}
              ],
              "designDecisions": ["a specific, defensible design decision"],
              "apiEndpoints": ["METHOD /path - what it does"],
              "clarificationQuestions": [],
              "notes": "1-3 sentences of overall design rationale"
            }
            """;

    private static final String BROWNFIELD_SYSTEM_PROMPT = """
            You are a senior software architect making a targeted change to
            an EXISTING, WORKING Spring Boot application. The source tree
            below is real and current. Your job is to find the MINIMAL set
            of files to touch.
            Rules:
            - Prefer MODIFY over CREATE whenever a file that could plausibly
              own the relevant logic already exists below.
            - Reuse existing packages, controllers, services, repositories,
              and entities - do not propose recreating anything already listed.
            - Every existing REST endpoint and public method not directly
              related to this change must be preserved (Implementation will
              enforce this, but your plan should not ask it to break them).
            Respond with ONLY valid JSON, no markdown fences, no commentary,
            matching exactly:
            {
              "impactedFiles": [
                {"path": "relative/path/from/source/tree/below.java", "changeType": "CREATE or MODIFY", "reason": "why"}
              ],
              "designDecisions": ["a specific, defensible design decision"],
              "apiEndpoints": ["METHOD /path - what it does (existing endpoints you're preserving AND any new ones)"],
              "clarificationQuestions": [],
              "notes": "1-3 sentences of overall design rationale"
            }
            """;

    private static final String AMBIGUOUS_SYSTEM_PROMPT = """
            The requirement behind this request was flagged as ambiguous at
            the requirements stage. Do NOT attempt a design yet - guessing at
            architecture for an underspecified request produces work that
            will likely need to be thrown away.
            Instead, review the flagged ambiguities and clarification
            questions and return them (refined/expanded if you can make them
            more specific) so a human can resolve them before design proceeds.
            Respond with ONLY valid JSON, no markdown fences, no commentary,
            matching exactly:
            {
              "impactedFiles": [],
              "designDecisions": [],
              "apiEndpoints": [],
              "clarificationQuestions": ["a specific question that must be answered before architecture can proceed"],
              "notes": "why design is being deferred"
            }
            """;

    private final AgentLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final CodebaseContextService codebase;

    public ArchitectureAgent(AgentLlmClient llmClient, ObjectMapper objectMapper, CodebaseContextService codebase) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.codebase = codebase;
    }

    @Override
    public Stage stage() {
        return Stage.ARCHITECTURE;
    }

    @Override
    public StageResult execute(WorkflowState state) {
        ScenarioType scenario = state.getScenarioType();
        boolean clarified = !state.getClarificationAnswers().isEmpty();

        // Once a human has answered a clarification question, always design
        // with real codebase context (safer default than guessing greenfield)
        // even if the original scenarioType was AMBIGUOUS.
        String systemPrompt = clarified ? BROWNFIELD_SYSTEM_PROMPT : switch (scenario) {
            case GREENFIELD -> GREENFIELD_SYSTEM_PROMPT;
            case BROWNFIELD -> BROWNFIELD_SYSTEM_PROMPT;
            case AMBIGUOUS -> AMBIGUOUS_SYSTEM_PROMPT;
        };

        String userPrompt = buildUserPrompt(scenario, state, clarified);
        String raw = llmClient.complete(systemPrompt, userPrompt);

        try {
            ArchitectureDesign design = objectMapper.readValue(
                    JsonExtractionUtil.extractJson(raw), ArchitectureDesign.class);
            state.setDesign(design);

            boolean deferred = design.impactedFiles() == null || design.impactedFiles().isEmpty();
            if (deferred) {
                state.addDecision(Stage.ARCHITECTURE, Actor.AGENT,
                        "Design deferred pending clarification: " + design.clarificationQuestions());
            } else {
                state.addDecision(Stage.ARCHITECTURE, Actor.AGENT,
                        "Design produced (%s): %d impacted file(s), %d API endpoint(s), %d design decisions."
                                .formatted(scenario, design.impactedFiles().size(),
                                        sizeOf(design.apiEndpoints()), sizeOf(design.designDecisions())));
            }
            return new StageResult(NodeOutcome.SUCCESS, "Architecture stage complete - awaiting human review gate.");
        } catch (Exception e) {
            state.addDecision(Stage.ARCHITECTURE, Actor.SYSTEM,
                    "Failed to parse architecture design JSON: " + e.getMessage());
            return new StageResult(NodeOutcome.FAILURE, "Could not parse LLM output as valid ArchitectureDesign JSON.");
        }
    }

    private String buildUserPrompt(ScenarioType scenario, WorkflowState state, boolean clarified) {
        String specBlock = """
                Approved requirement spec:
                functionalRequirements: %s
                nonFunctionalRequirements: %s
                acceptanceCriteria: %s
                assumptions: %s
                """.formatted(
                state.getSpec() != null ? state.getSpec().functionalRequirements() : "(none)",
                state.getSpec() != null ? state.getSpec().nonFunctionalRequirements() : "(none)",
                state.getSpec() != null ? state.getSpec().acceptanceCriteria() : "(none)",
                state.getSpec() != null ? state.getSpec().assumptions() : "(none)");

        if (clarified) {
            return "SCHEMA_ID: ARCHITECTURE_DESIGN_V1\n" + specBlock
                    + "\nHuman clarification answer(s) received - design against these, not the original ambiguity: "
                    + state.getClarificationAnswers()
                    + "\nExisting source tree (shortener-service/src/main/java, relative paths):\n"
                    + codebase.summarizeSourceTree();
        }

        return switch (scenario) {
            case GREENFIELD -> "SCHEMA_ID: ARCHITECTURE_DESIGN_V1\n" + specBlock;
            case BROWNFIELD -> "SCHEMA_ID: ARCHITECTURE_DESIGN_V1\n" + specBlock
                    + "\nExisting source tree (shortener-service/src/main/java, relative paths):\n"
                    + codebase.summarizeSourceTree();
            case AMBIGUOUS -> "SCHEMA_ID: ARCHITECTURE_DESIGN_V1\n" + specBlock
                    + "\nAmbiguities flagged at requirements stage: "
                    + (state.getSpec() != null ? state.getSpec().ambiguities() : List.of())
                    + "\nClarification questions already raised: "
                    + (state.getSpec() != null ? state.getSpec().clarificationQuestions() : List.of());
        };
    }

    private int sizeOf(List<String> list) {
        return list == null ? 0 : list.size();
    }
}
