package com.chatdemo.common.service

import com.chatdemo.common.config.ProviderConfig
import com.chatdemo.common.model.{Conversation, ConversationMessage, MessageAttachment, UserContext}

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
  def createConversation(userContext: UserContext, conversationId: String): Boolean

  /** Get full message history for a conversation. */
  def getConversationHistory(userContext: UserContext, conversationId: String): List[ConversationMessage]

  /** List all known conversations for the given user. */
  def listConversations(userContext: UserContext): List[Conversation]

  /** Set or update a conversation title. */
  def setConversationTitle(userContext: UserContext, conversationId: String, title: String): Unit

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
   * @param userContext    user context containing premium status and identity
   * @param streamHandler  callback that receives streaming tokens
   */
  def chat(
    conversationId: String,
    message: String,
    attachments: List[MessageAttachment],
    modelIndex: Int,
    userContext: UserContext,
    streamHandler: ChatStreamHandler
  ): ChatResult

  def clearConversation(userContext: UserContext, conversationId: String): Unit
}
