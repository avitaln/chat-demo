package com.chatdemo.config;

import com.chatdemo.ProviderConfig;

import java.util.List;

/**
 * Configuration for available AI models.
 * Update the API keys with your actual keys before running.
 */
public class ModelsConfig {

    private static final String geminiKey = System.getenv("GEMINI_API_KEY");
    private static final String openaiKey = System.getenv("OPENAI_API_KEY");
    private static final String claudeKey = System.getenv("ANTHROPIC_API_KEY");
    private static final String grokKey = System.getenv("XAI_API_KEY");
    
    /**
     * List of configured AI providers.
     * Each provider requires:
     * - providerType: "gemini", "chatgpt", "claude", or "grok"
     * - model: The specific model to use
     * - apiKey: Your API key for that provider
     */
    public static final List<ProviderConfig> PROVIDERS = List.of(
        new ProviderConfig("gemini", "gemini-2.0-flash", geminiKey),
        new ProviderConfig("chatgpt", "gpt-4o", openaiKey),
        new ProviderConfig("claude", "claude-sonnet-4-20250514", claudeKey),
        new ProviderConfig("grok", "grok-4", grokKey)
    );

    public static String getOpenAiKey() {
        return openaiKey;
    }

    public static String getGeminiKey() {
        return geminiKey;
    }

    public static String getGrokKey() {
        return grokKey;
    }
}
