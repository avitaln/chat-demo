package com.chatdemo.backend.memory

import com.chatdemo.backend.repository.ConversationRepository
import com.chatdemo.common.model.ConversationMessage
import dev.langchain4j.data.message.{AiMessage, ChatMessage, SystemMessage, UserMessage}
import dev.langchain4j.store.memory.chat.ChatMemoryStore

import java.util.{List => JList}
import scala.jdk.CollectionConverters.*

/**
 * ChatMemoryStore adapter that bridges LangChain4j's memory system to our ConversationRepository.
 *
 * This store:
 * - Returns summary + active messages when getMessages() is called
 * - Detects newly added messages and persists them
 * - Does NOT handle archiving directly (that's done by SummarizingTokenWindowChatMemory)
 */
class HistoryAwareChatMemoryStore(private val repository: ConversationRepository) extends ChatMemoryStore {

  override def getMessages(memoryId: AnyRef): JList[ChatMessage] = {
    val conversationId = memoryId.toString
    val result = new java.util.ArrayList[ChatMessage]()

    // Add summary as SystemMessage if present
    val summary = repository.getSummary(conversationId)
    if (summary != null && summary.nonEmpty) {
      result.add(SystemMessage.from("Summary of earlier conversation:\n" + summary))
    }

    // Add active (non-archived) messages.
    // Skip persisted system prompts so stale prompt versions do not affect future turns.
    for (msg <- repository.getActiveMessages(conversationId)) {
      msg match {
        case _: SystemMessage => // skip persisted system prompts
        case _ =>
          if (!isNoiseMessage(msg)) {
            result.add(msg)
          }
      }
    }

    result
  }

  override def updateMessages(memoryId: AnyRef, messages: JList[ChatMessage]): Unit = {
    val conversationId = memoryId.toString
    val currentMessages = repository.getActiveMessages(conversationId)

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
            repository.addMessage(conversationId, message)
          }
      }
    }
  }

  override def deleteMessages(memoryId: AnyRef): Unit = {
    repository.clear(memoryId.toString)
  }

  /** Archives messages and updates the summary. */
  def archiveMessages(conversationId: String, messageIds: List[String], summary: String): Unit = {
    repository.archiveMessages(conversationId, messageIds, summary)
  }

  /** Get message IDs for active messages. */
  def getActiveMessageIds(conversationId: String): List[String] = {
    repository.getFullHistory(conversationId)
      .filter(!_.archived)
      .map(_.id)
  }

  /** Get full history for UI display. */
  def getFullHistory(conversationId: String): List[ConversationMessage] = {
    repository.getFullHistory(conversationId)
  }

  private def containsMessage(messages: List[ChatMessage], target: ChatMessage): Boolean = {
    messages.exists(msg => messagesEqual(msg, target))
  }

  private def isNoiseMessage(message: ChatMessage): Boolean = {
    message match {
      case ai: AiMessage =>
        val text = ai.text()
        text == null || text.isBlank
      case user: UserMessage =>
        val text = user.singleText()
        text == null || text.isBlank || text.startsWith("ToolExecutionResultMessage")
      case _ => false
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
