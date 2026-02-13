package com.chatdemo.backend.model

import com.chatdemo.common.config.ProviderConfig
import dev.langchain4j.model.chat.{ChatModel, StreamingChatModel}
import dev.langchain4j.model.anthropic.{AnthropicChatModel, AnthropicStreamingChatModel}
import dev.langchain4j.model.googleai.{GoogleAiGeminiChatModel, GoogleAiGeminiStreamingChatModel}
import dev.langchain4j.model.image.ImageModel
import dev.langchain4j.model.openai.{OpenAiChatModel, OpenAiImageModel, OpenAiStreamingChatModel}

/**
 * Factory for creating LangChain4j ChatModel instances.
 */
object ModelFactory {

  private val GrokBaseUrl = "https://api.x.ai/v1"
  private val QwenApiBaseUrl = "https://dashscope-intl.aliyuncs.com/api/v1"
  private val OpenAiImageModelName = "dall-e-3"
  private val GeminiImageModelName = "gemini-2.5-flash-image"
  private val GrokImageModelName = "grok-imagine-image"
  private val QwenCreateImageModelName = "qwen-image-max"
  private val QwenEditImageModelName = "qwen-image-edit-max"

  /** Create a ChatModel based on the provider configuration. */
  def createModel(config: ProviderConfig): ChatModel = {
    config.providerType.toLowerCase match {
      case "gemini"  => createGeminiModel(config)
      case "chatgpt" => createOpenAIModel(config)
      case "claude"  => createClaudeModel(config)
      case "grok"    => createGrokModel(config)
      case _         => throw new IllegalArgumentException("Unknown provider: " + config.providerType)
    }
  }

  /** Create a StreamingChatModel based on the provider configuration. */
  def createStreamingModel(config: ProviderConfig): StreamingChatModel = {
    config.providerType.toLowerCase match {
      case "gemini"  => createGeminiStreamingModel(config)
      case "chatgpt" => createOpenAiStreamingModel(config)
      case "claude"  => createClaudeStreamingModel(config)
      case "grok"    => createGrokStreamingModel(config)
      case _         => throw new IllegalArgumentException("Unknown provider: " + config.providerType)
    }
  }

  /** Create a Gemini ChatModel for image generation outputs. */
  def createGeminiImageModel(apiKey: String, modelName: String): ChatModel = {
    GoogleAiGeminiChatModel.builder()
      .apiKey(apiKey)
      .modelName(modelName)
      .build()
  }

  /** Create an ImageModel for OpenAI image generation. */
  def createImageModel(apiKey: String): ImageModel = {
    createImageModel(apiKey, OpenAiImageModelName)
  }

  def createImageModel(apiKey: String, modelName: String): ImageModel = {
    OpenAiImageModel.builder()
      .apiKey(apiKey)
      .modelName(modelName)
      .responseFormat("url")
      .build()
  }

  def createGrokImageModel(apiKey: String, modelName: String): ImageModel = {
    OpenAiImageModel.builder()
      .apiKey(apiKey)
      .modelName(modelName)
      .baseUrl(GrokBaseUrl)
      .build()
  }

  def createQwenImageModel(apiKey: String, modelName: String): ImageModel = {
    OpenAiImageModel.builder()
      .apiKey(apiKey)
      .modelName(modelName)
      .baseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
      .responseFormat("url")
      .build()
  }

  def defaultOpenAiImageModel: String = OpenAiImageModelName
  def defaultGeminiImageModel: String = GeminiImageModelName
  def defaultGrokImageModel: String = GrokImageModelName
  def defaultQwenCreateImageModel: String = QwenCreateImageModelName
  def defaultQwenEditImageModel: String = QwenEditImageModelName
  def qwenApiBaseUrl: String = QwenApiBaseUrl

  private def createGeminiModel(config: ProviderConfig): ChatModel = {
    GoogleAiGeminiChatModel.builder()
      .apiKey(config.apiKey)
      .modelName(config.model)
      .build()
  }

  private def createGeminiStreamingModel(config: ProviderConfig): StreamingChatModel = {
    GoogleAiGeminiStreamingChatModel.builder()
      .apiKey(config.apiKey)
      .modelName(config.model)
      .build()
  }

  private def createOpenAIModel(config: ProviderConfig): ChatModel = {
    OpenAiChatModel.builder()
      .apiKey(config.apiKey)
      .modelName(config.model)
      .build()
  }

  private def createOpenAiStreamingModel(config: ProviderConfig): StreamingChatModel = {
    OpenAiStreamingChatModel.builder()
      .apiKey(config.apiKey)
      .modelName(config.model)
      .build()
  }

  private def createClaudeModel(config: ProviderConfig): ChatModel = {
    AnthropicChatModel.builder()
      .apiKey(config.apiKey)
      .modelName(config.model)
      .build()
  }

  private def createClaudeStreamingModel(config: ProviderConfig): StreamingChatModel = {
    AnthropicStreamingChatModel.builder()
      .apiKey(config.apiKey)
      .modelName(config.model)
      .build()
  }

  private def createGrokModel(config: ProviderConfig): ChatModel = {
    OpenAiChatModel.builder()
      .apiKey(config.apiKey)
      .modelName(config.model)
      .baseUrl(GrokBaseUrl)
      .build()
  }

  private def createGrokStreamingModel(config: ProviderConfig): StreamingChatModel = {
    OpenAiStreamingChatModel.builder()
      .apiKey(config.apiKey)
      .modelName(config.model)
      .baseUrl(GrokBaseUrl)
      .build()
  }
}
