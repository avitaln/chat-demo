package com.chatdemo;

/**
 * Configuration record for an AI provider.
 * 
 * @param providerType The type of provider (gemini, chatgpt, claude, grok)
 * @param model The model identifier to use
 * @param apiKey The API key for authentication
 */
public record ProviderConfig(
    String providerType,
    String model,
    String apiKey
) {
    /**
     * Get a display name for this configuration.
     * 
     * @return Formatted display name like "ChatGPT (gpt-4o)"
     */
    public String getDisplayName() {
        String name = switch (providerType.toLowerCase()) {
            case "gemini" -> "Gemini";
            case "chatgpt" -> "ChatGPT";
            case "claude" -> "Claude";
            case "grok" -> "Grok";
            default -> providerType;
        };
        return name + " (" + model + ")";
    }
}
