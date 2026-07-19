package com.example.orchestrator.llm;

/**
 * Every agent talks to the model only through this interface. Swapping
 * AnthropicLlmClient <-> MockLlmClient (see app.llm.mode in application.yml)
 * changes zero agent code - this is what lets you demo the whole pipeline
 * without spending API credits, then flip one property for the real run.
 */
public interface AgentLlmClient {
    String complete(String systemPrompt, String userPrompt);
}
