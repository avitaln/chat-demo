package com.chatdemo.common.service

import com.chatdemo.common.config.ProviderConfig
import com.chatdemo.common.model.{ConversationMessage, MessageAttachment}

/**
 * Contract between the client application and the backend.
 * The app layer depends only on this trait and common model types.
 *
 * The backend is stateless per request: model selection and attachments
 * are provided inline with each chat call rather than staged server-side.
 */
trait ChatBackend {

  // ---- Conversation management ----

  /** Create a new conversation. Returns true if created, false if it already existed. */
  def createConversation(conversationId: String): Boolean

  /** Get full message history for a conversation. */
  def getConversationHistory(conversationId: String): List[ConversationMessage]

  /** List all known conversation IDs. */
  def listConversations(): List[String]

  // ---- Model management (read-only) ----

  /** Available models the client can choose from. */
  def getAvailableModels: List[ProviderConfig]

  // ---- Chat ----

  /**
   * Send a chat message. The caller specifies the model and attachments inline.
   *
   * @param conversationId conversation to chat in
   * @param message        the user's message text
   * @param attachments    attachments for this turn (images as links, documents, etc.)
   * @param modelIndex     index into getAvailableModels for the model to use
   * @param isPremium      whether the user has premium entitlement for gated features
   * @param streamHandler  callback that receives streaming tokens
   */
  def chat(
    conversationId: String,
    message: String,
    attachments: List[MessageAttachment],
    modelIndex: Int,
    isPremium: Boolean,
    streamHandler: ChatStreamHandler
  ): ChatResult

  def clearConversation(conversationId: String): Unit
}
