package com.chatdemo;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * Factory for creating LangChain4j ChatLanguageModel instances.
 */
public class ModelFactory {
    
    private static final String GROK_BASE_URL = "https://api.x.ai/v1";
    
    /**
     * Create a ChatLanguageModel based on the provider configuration.
     */
    public static ChatLanguageModel createModel(ProviderConfig config) {
        return switch (config.providerType().toLowerCase()) {
            case "gemini" -> createGeminiModel(config);
            case "chatgpt" -> createOpenAIModel(config);
            case "claude" -> createClaudeModel(config);
            case "grok" -> createGrokModel(config);
            default -> throw new IllegalArgumentException("Unknown provider: " + config.providerType());
        };
    }
    
    private static ChatLanguageModel createGeminiModel(ProviderConfig config) {
        return GoogleAiGeminiChatModel.builder()
            .apiKey(config.apiKey())
            .modelName(config.model())
            .build();
    }
    
    private static ChatLanguageModel createOpenAIModel(ProviderConfig config) {
        return OpenAiChatModel.builder()
            .apiKey(config.apiKey())
            .modelName(config.model())
            .build();
    }
    
    private static ChatLanguageModel createClaudeModel(ProviderConfig config) {
        return AnthropicChatModel.builder()
            .apiKey(config.apiKey())
            .modelName(config.model())
            .build();
    }
    
    private static ChatLanguageModel createGrokModel(ProviderConfig config) {
        // Grok uses OpenAI-compatible API with custom base URL
        return OpenAiChatModel.builder()
            .apiKey(config.apiKey())
            .modelName(config.model())
            .baseUrl(GROK_BASE_URL)
            .build();
    }
}
