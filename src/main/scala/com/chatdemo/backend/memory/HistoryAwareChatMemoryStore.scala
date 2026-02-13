package com.chatdemo.backend.memory

import com.chatdemo.backend.repository.ConversationRepository
import com.chatdemo.common.model.{ConversationMessage, UserContext}
import dev.langchain4j.data.message.{AiMessage, ChatMessage, SystemMessage, UserMessage}
import dev.langchain4j.store.memory.chat.ChatMemoryStore

import java.util.{List => JList}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/**
 * ChatMemoryStore adapter that bridges LangChain4j's memory system to our ConversationRepository.
 *
 * This store:
 * - Returns summary + active messages when getMessages() is called
 * - Detects newly added messages and persists them
 * - Does NOT handle archiving directly (that's done by SummarizingTokenWindowChatMemory)
 */
class HistoryAwareChatMemoryStore(
  private val repository: ConversationRepository,
  private val userContext: UserContext
) extends ChatMemoryStore {

  private val persistedMessages: ArrayBuffer[ChatMessage] = ArrayBuffer.empty

  override def getMessages(memoryId: AnyRef): JList[ChatMessage] = {
    val conversationId = memoryId.toString
    val result = new java.util.ArrayList[ChatMessage]()
    persistedMessages.clear()

    // Add summary as SystemMessage if present
    val summary = repository.getSummary(userContext, conversationId)
    if (summary != null && summary.nonEmpty) {
      result.add(SystemMessage.from("Summary of earlier conversation:\n" + summary))
    }

    // Add active (non-archived) messages.
    // Skip persisted system prompts so stale prompt versions do not affect future turns.
    for (msg <- repository.getActiveMessages(userContext, conversationId)) {
      msg match {
        case _: SystemMessage => // skip persisted system prompts
        case _ =>
          if (!isNoiseMessage(msg)) {
            result.add(msg)
            persistedMessages.append(msg)
          }
      }
    }

    result
  }

  override def updateMessages(memoryId: AnyRef, messages: JList[ChatMessage]): Unit = {
    val conversationId = memoryId.toString
    val currentMessages = persistedMessages

    for (message <- messages.asScala) {
      // Skip summary system messages
      message match {
        case sm: SystemMessage if sm.text().startsWith("Summary of earlier conversation") =>
          // skip
        case _: SystemMessage =>
          // Do not persist runtime system prompts in conversation history.
          // The current system prompt is injected per-request.
        case _ =>
          if (!isNoiseMessage(message) && !containsMessage(currentMessages, message)) {
            repository.addMessage(userContext, conversationId, message)
            currentMessages.append(message)
          }
      }
    }
  }

  override def deleteMessages(memoryId: AnyRef): Unit = {
    repository.clear(userContext, memoryId.toString)
  }

  /** Archives messages and updates the summary. */
  def archiveMessages(conversationId: String, messageIds: List[String], summary: String): Unit = {
    repository.archiveMessages(userContext, conversationId, messageIds, summary)
  }

  /** Get message IDs for active messages. */
  def getActiveMessageIds(conversationId: String): List[String] = {
    repository.getFullHistory(userContext, conversationId)
      .filter(!_.archived)
      .map(_.id)
  }

  /** Get full history for UI display. */
  def getFullHistory(conversationId: String): List[ConversationMessage] = {
    repository.getFullHistory(userContext, conversationId)
  }

  private def containsMessage(messages: scala.collection.Iterable[ChatMessage], target: ChatMessage): Boolean = {
    messages.exists(msg => messagesEqual(msg, target))
  }

  private def isNoiseMessage(message: ChatMessage): Boolean = {
    def containsToolResultMarker(text: String): Boolean = {
      text != null && text.contains("ToolExecutionResultMessage")
    }

    message match {
      case ai: AiMessage =>
        val text = ai.text()
        text == null || text.isBlank || containsToolResultMarker(text)
      case user: UserMessage =>
        val text = user.singleText()
        text == null || text.isBlank || containsToolResultMarker(text)
      case other =>
        val text = other.toString
        text == null || text.isBlank || containsToolResultMarker(text)
    }
  }

  private def messagesEqual(a: ChatMessage, b: ChatMessage): Boolean = {
    if (a.getClass != b.getClass) {
      false
    } else {
      a == b
    }
  }
}
