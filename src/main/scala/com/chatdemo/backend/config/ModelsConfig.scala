package com.chatdemo.backend.config

import com.chatdemo.common.config.ProviderConfig

/**
 * Configuration for available AI models.
 * Update the API keys with your actual keys before running.
 */
object ModelsConfig {

  private val geminiKey: String = System.getenv("GEMINI_API_KEY")
  private val openaiKey: String = System.getenv("OPENAI_API_KEY")
  private val claudeKey: String = System.getenv("ANTHROPIC_API_KEY")
  private val grokKey: String   = System.getenv("XAI_API_KEY")

  /**
   * List of configured AI providers.
   */
  val Providers: List[ProviderConfig] = List(
    ProviderConfig("gemini", "gemini-2.0-flash", geminiKey),
    ProviderConfig("chatgpt", "gpt-4o", openaiKey),
    ProviderConfig("claude", "claude-sonnet-4-20250514", claudeKey),
    ProviderConfig("grok", "grok-4", grokKey)
  )

  def getOpenAiKey: String = openaiKey
  def getGeminiKey: String = geminiKey
  def getGrokKey: String = grokKey
}
