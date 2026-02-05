package com.chatdemo;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiImageModel;

/**
 * Factory for creating LangChain4j ChatLanguageModel instances.
 */
public class ModelFactory {
    
    private static final String GROK_BASE_URL = "https://api.x.ai/v1";
    private static final String OPENAI_IMAGE_MODEL = "dall-e-3";
    private static final String GEMINI_IMAGE_MODEL = "gemini-2.5-flash-image";
    
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

    /**
     * Create a Gemini ChatModel for image generation outputs.
     */
    public static ChatModel createGeminiImageModel(String apiKey, String modelName) {
        return GoogleAiGeminiChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .build();
    }

    /**
     * Create an ImageModel for OpenAI image generation.
     */
    public static ImageModel createImageModel(String apiKey) {
        return createImageModel(apiKey, OPENAI_IMAGE_MODEL);
    }

    public static ImageModel createImageModel(String apiKey, String modelName) {
        return OpenAiImageModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .responseFormat("url")
            .build();
    }

    public static String defaultOpenAiImageModel() {
        return OPENAI_IMAGE_MODEL;
    }

    public static String defaultGeminiImageModel() {
        return GEMINI_IMAGE_MODEL;
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
