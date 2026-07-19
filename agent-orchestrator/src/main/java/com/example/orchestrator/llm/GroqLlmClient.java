package com.example.orchestrator.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Groq's API is OpenAI-compatible, so this rides Spring AI's OpenAI starter
 * rather than a bespoke HTTP client - configure it via spring.ai.openai.*
 * in application.yml, pointing base-url at Groq (see application.yml).
 *
 * Built from OpenAiChatModel directly (not the auto-configured
 * ChatClient.Builder) so that having both the Anthropic and OpenAI starters
 * on the classpath at once - needed to support both providers - can't cause
 * an ambiguous-bean startup failure.
 */
@Component
@ConditionalOnProperty(prefix = "app.llm", name = "mode", havingValue = "groq")
public class GroqLlmClient implements AgentLlmClient {

    private final ChatClient chatClient;

    public GroqLlmClient(OpenAiChatModel chatModel) {
        this.chatClient = ChatClient.create(chatModel);
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return LlmRetrySupport.callWithBackoff(() -> chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content());
    }
}
