package com.chatdemo.common.config

/**
 * Configuration for an AI provider.
 *
 * @param providerType The type of provider (gemini, chatgpt, claude, grok)
 * @param model        The model identifier to use
 * @param apiKey       The API key for authentication
 */
case class ProviderConfig(providerType: String, model: String, apiKey: String) {

  /** Formatted display name like "ChatGPT (gpt-4o)". */
  def getDisplayName: String = {
    val name = providerType.toLowerCase match {
      case "gemini"  => "Gemini"
      case "chatgpt" => "ChatGPT"
      case "claude"  => "Claude"
      case "grok"    => "Grok"
      case _         => providerType
    }
    s"$name ($model)"
  }
}
