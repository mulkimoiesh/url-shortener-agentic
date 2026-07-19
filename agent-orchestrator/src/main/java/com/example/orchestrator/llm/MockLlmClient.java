package com.example.orchestrator.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic canned responses, selected by a SCHEMA_ID marker each agent
 * puts in its user prompt. This is the default (app.llm.mode=mock or unset)
 * so you can exercise the full graph - gates, retries, audit log, metrics -
 * before spending a single API credit. Flip app.llm.mode=anthropic once
 * ANTHROPIC_API_KEY is set to get real model output instead.
 */
@Component
@ConditionalOnProperty(prefix = "app.llm", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements AgentLlmClient {

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (userPrompt.contains("SCHEMA_ID: REQUIREMENTS_SPEC_V1")) {
            return requirementsSpecResponse(userPrompt);
        }
        if (userPrompt.contains("SCHEMA_ID: ARCHITECTURE_DESIGN_V1")) {
            return architectureDesignResponse();
        }
        if (userPrompt.contains("SCHEMA_ID: IMPLEMENTATION_V1")) {
            return implementationResponse(userPrompt);
        }
        return "{}";
    }

    private String architectureDesignResponse() {
        return """
                {
                  "impactedFiles": [
                    {"path": "src/main/java/com/example/shortener/controller/ShortenController.java", "changeType": "MODIFY", "reason": "MOCK MODE: canned example impacted file - set app.llm.mode=anthropic for a real design"}
                  ],
                  "designDecisions": [
                    "MOCK MODE: canned decision - see MockLlmClient"
                  ],
                  "apiEndpoints": [
                    "GET /{code} - MOCK MODE: canned example endpoint"
                  ],
                  "clarificationQuestions": [],
                  "notes": "This is a canned mock response so the pipeline can run end-to-end without API calls."
                }
                """;
    }

    private String implementationResponse(String userPrompt) {
        boolean guardrailDemo = userPrompt.toUpperCase().contains("DEMO_GUARDRAIL_TRIGGER");

        String secretFileEntry = guardrailDemo ? """
                ,
                    {
                      "path": "DEMO_INSECURE_FILE.java",
                      "content": "// [DEMO] deliberately insecure snippet to exercise the Guardrail Agent\\npublic class DemoInsecure {\\n    String apiKey = \\"AKIAABCDEFGHIJKLMNOP\\";\\n}\\n",
                      "action": "CREATE"
                    }""" : "";

        return """
                {
                  "files": [
                    {
                      "path": "MOCK_GENERATED_NOTE.md",
                      "content": "This file was written by the Implementation Agent using MOCK responses.\\n\\nIt landed in this run's isolated workspace (run-artifacts/{runId}/workspace/shortener-service/), NOT the live shortener-service module - the live product is only ever touched by the explicit POST /runs/{id}/apply call after a run reaches COMPLETED.\\n\\nSet app.llm.mode=anthropic and ANTHROPIC_API_KEY (or wire your own AgentLlmClient) to have this agent generate and write real Java code.\\n",
                      "action": "CREATE"
                    }%s
                  ],
                  "notes": "MOCK MODE: canned change, written to this run's isolated workspace only."
                }
                """.formatted(secretFileEntry);
    }

    private String requirementsSpecResponse(String userPrompt) {
        String requirementLine = userPrompt.lines()
                .filter(l -> !l.isBlank())
                .reduce((a, b) -> b) // last non-blank line = the raw requirement text
                .orElse("the requested feature");

        return """
                {
                  "functionalRequirements": [
                    "The system must support: %s"
                  ],
                  "nonFunctionalRequirements": [
                    "MOCK MODE: no real non-functional analysis performed - set app.llm.mode=anthropic for a real one"
                  ],
                  "acceptanceCriteria": [
                    "The API responds with the expected data for the new/changed behavior.",
                    "Existing endpoints continue to pass their current tests (no regression)."
                  ],
                  "ambiguities": [
                    "MOCK MODE: this is a canned response - set app.llm.mode=anthropic and ANTHROPIC_API_KEY to get a real requirements analysis for: %s"
                  ],
                  "clarificationQuestions": [],
                  "assumptions": [
                    "No authentication/authorization changes are in scope unless stated explicitly."
                  ]
                }
                """.formatted(escape(requirementLine), escape(requirementLine));
    }

    private String escape(String s) {
        return s.replace("\"", "'");
    }
}
