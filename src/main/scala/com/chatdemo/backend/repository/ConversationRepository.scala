package com.chatdemo.backend.repository

import com.chatdemo.common.model.{ConversationMessage, MessageAttachment}
import dev.langchain4j.data.message.ChatMessage

/**
 * Repository interface for conversation persistence.
 * Separates concerns between full history (for UI) and active messages (for LLM).
 */
trait ConversationRepository {

  /** Create a new conversation with the given ID. Returns true if created, false if it already exists. */
  def createConversation(conversationId: String): Boolean

  /** Check whether a conversation exists. */
  def conversationExists(conversationId: String): Boolean

  /** List all conversation IDs. */
  def listConversationIds(): List[String]

  /** Get full conversation history including archived messages. Used for displaying to the user. */
  def getFullHistory(conversationId: String): List[ConversationMessage]

  /** Get only active (non-archived) messages for the LLM. */
  def getActiveMessages(conversationId: String): List[dev.langchain4j.data.message.ChatMessage]

  /** Get the current summary of archived messages. */
  def getSummary(conversationId: String): String

  /** Add a new message to the conversation. */
  def addMessage(conversationId: String, message: dev.langchain4j.data.message.ChatMessage): Unit

  /** Attach metadata to the latest AI message in the conversation. */
  def attachToLatestAiMessage(conversationId: String, attachments: List[MessageAttachment]): Unit = {}

  /** Attach metadata to the latest User message in the conversation. */
  def attachToLatestUserMessage(conversationId: String, attachments: List[MessageAttachment]): Unit = {}

  /** Archive messages that have been summarized. */
  def archiveMessages(conversationId: String, messageIds: List[String], newSummary: String): Unit

  /** Clear all messages and summary for a conversation. */
  def clear(conversationId: String): Unit
}
