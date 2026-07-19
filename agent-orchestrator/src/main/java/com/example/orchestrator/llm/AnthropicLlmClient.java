package com.example.orchestrator.llm;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.llm", name = "mode", havingValue = "anthropic")
public class AnthropicLlmClient implements AgentLlmClient {

    private final ChatClient chatClient;

    public AnthropicLlmClient(AnthropicChatModel chatModel) {
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
