package com.chatdemo.backend.provider

import com.chatdemo.backend.config.ModelsConfig
import com.chatdemo.common.config.ProviderConfig

class HardcodedModelsProvider extends ModelsProvider {

  override val getAvailableModels: List[ProviderConfig] = List(
    ProviderConfig(id = "gemini-flash", providerType = "gemini", model = "gemini-2.0-flash", apiKey = ModelsConfig.getGeminiKey),
    ProviderConfig(id = "openai-gpt-4o", providerType = "chatgpt", model = "gpt-4o", apiKey = ModelsConfig.getOpenAiKey),
    ProviderConfig(id = "claude-sonnet-4", providerType = "claude", model = "claude-sonnet-4-20250514", apiKey = ModelsConfig.getClaudeKey),
    ProviderConfig(id = "grok-4", providerType = "grok", model = "grok-4", apiKey = ModelsConfig.getGrokKey)
  )
}
