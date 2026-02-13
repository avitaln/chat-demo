package com.chatdemo.backend.repository

import com.chatdemo.common.model.{Conversation, ConversationMessage, MessageAttachment, UserContext}
import dev.langchain4j.data.message.ChatMessage

/**
 * Repository interface for conversation persistence.
 * Separates concerns between full history (for UI) and active messages (for LLM).
 */
trait ConversationRepository {

  /** Create a new conversation with the given ID, scoped to the user. Returns true if created, false if it already exists. */
  def createConversation(userContext: UserContext, conversationId: String): Boolean

  /** List all conversations for the given user. */
  def listConversations(userContext: UserContext): List[Conversation]

  /** Set or update conversation title for the given user and conversation. */
  def setConversationTitle(userContext: UserContext, conversationId: String, title: String): Unit

  /** Get full conversation history including archived messages. Used for displaying to the user. */
  def getFullHistory(userContext: UserContext, conversationId: String): List[ConversationMessage]

  /** Get only active (non-archived) messages for the LLM. */
  def getActiveMessages(userContext: UserContext, conversationId: String): List[dev.langchain4j.data.message.ChatMessage]

  /** Get the current summary of archived messages. */
  def getSummary(userContext: UserContext, conversationId: String): String

  /** Add a new message to the conversation. */
  def addMessage(userContext: UserContext, conversationId: String, message: dev.langchain4j.data.message.ChatMessage): Unit

  /** Attach metadata to the latest AI message in the conversation. */
  def attachToLatestAiMessage(userContext: UserContext, conversationId: String, attachments: List[MessageAttachment]): Unit = {}

  /** Attach metadata to the latest User message in the conversation. */
  def attachToLatestUserMessage(userContext: UserContext, conversationId: String, attachments: List[MessageAttachment]): Unit = {}

  /** Archive messages that have been summarized. */
  def archiveMessages(userContext: UserContext, conversationId: String, messageIds: List[String], newSummary: String): Unit

  /** Clear all messages and summary for a conversation. */
  def clear(userContext: UserContext, conversationId: String): Unit
}
