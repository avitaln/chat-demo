package com.chatdemo.backend.memory

import dev.langchain4j.data.message.{AiMessage, ChatMessage, SystemMessage, UserMessage}
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.model.TokenCountEstimator
import dev.langchain4j.model.chat.ChatModel

import java.util.{List => JList}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/**
 * Custom ChatMemory implementation that summarizes old messages when token limit is exceeded.
 */
class SummarizingTokenWindowChatMemory private (
  private val memoryId: AnyRef,
  private val maxTokens: Int,
  private val tokenCountEstimator: TokenCountEstimator,
  private val summarizationModel: ChatModel,
  private val memoryStore: HistoryAwareChatMemoryStore
) extends ChatMemory {

  private val SummarizationPrompt: String =
    """Summarize the following conversation concisely, preserving key information,
      |decisions made, user preferences, and important context that would be needed
      |to continue the conversation naturally. Focus on facts, not the conversation flow.
      |
      |Conversation to summarize:
      |%s
      |
      |Provide a concise summary:""".stripMargin

  private val SummaryPrefix: String = "Summary of earlier conversation:\n"

  private val messageBuffer: ArrayBuffer[ChatMessage] = ArrayBuffer.from(memoryStore.getMessages(memoryId).asScala)

  override def id(): AnyRef = memoryId

  override def add(message: ChatMessage): Unit = {
    messageBuffer.append(message)
    ensureCapacity()
    memoryStore.updateMessages(memoryId, new java.util.ArrayList[ChatMessage](messageBuffer.asJava))
  }

  override def messages(): JList[ChatMessage] = {
    new java.util.ArrayList[ChatMessage](messageBuffer.asJava)
  }

  override def clear(): Unit = {
    messageBuffer.clear()
    memoryStore.deleteMessages(memoryId)
  }

  private def ensureCapacity(): Unit = {
    val currentTokens = countTokens(messageBuffer.toList)
    if (currentTokens <= maxTokens) {
      return
    }

    val messagesToSummarize = ArrayBuffer.empty[ChatMessage]
    val messageIdsToArchive = ArrayBuffer.empty[String]
    var summaryIndex = -1

    // Check if we already have a summary
    for (i <- messageBuffer.indices) {
      messageBuffer(i) match {
        case sm: SystemMessage if sm.text().startsWith(SummaryPrefix) =>
          if (summaryIndex == -1) summaryIndex = i
        case _ => // skip
      }
    }

    val activeIds = memoryStore.getActiveMessageIds(memoryId.toString)
    val startIndex = if (summaryIndex >= 0) summaryIndex + 1 else 0
    val keepCount = 2

    var i = startIndex
    while (i < messageBuffer.size - keepCount) {
      val msg = messageBuffer(i)

      msg match {
        case sm: SystemMessage if sm.text().startsWith(SummaryPrefix) =>
          // skip existing summary
        case _ =>
          messagesToSummarize.append(msg)
          if (i - startIndex < activeIds.size) {
            messageIdsToArchive.append(activeIds(i - startIndex))
          }

          // Check if removing these messages would be enough
          val remaining = ArrayBuffer.empty[ChatMessage]
          if (summaryIndex >= 0) {
            remaining.append(messageBuffer(summaryIndex))
          }
          for (j <- (i + 1) until messageBuffer.size) {
            remaining.append(messageBuffer(j))
          }
          val estimatedNewTokens = countTokens(remaining.toList) + 200
          if (estimatedNewTokens <= maxTokens) {
            i = messageBuffer.size // break
          }
      }
      i += 1
    }

    if (messagesToSummarize.isEmpty) {
      return
    }

    val existingSummary = extractExistingSummary()
    val newSummary = generateSummary(existingSummary, messagesToSummarize.toList)

    if (messageIdsToArchive.nonEmpty) {
      memoryStore.archiveMessages(memoryId.toString, messageIdsToArchive.toList, newSummary)
    }

    val newMessages = ArrayBuffer.empty[ChatMessage]
    newMessages.append(SystemMessage.from(SummaryPrefix + newSummary))

    val summarizedCount = messagesToSummarize.size
    for (j <- (startIndex + summarizedCount) until messageBuffer.size) {
      val msg = messageBuffer(j)
      msg match {
        case sm: SystemMessage if sm.text().startsWith(SummaryPrefix) =>
          // skip old summary
        case _ =>
          newMessages.append(msg)
      }
    }

    messageBuffer.clear()
    messageBuffer.appendAll(newMessages)
  }

  private def extractExistingSummary(): String = {
    messageBuffer.collectFirst {
      case sm: SystemMessage if sm.text().startsWith(SummaryPrefix) =>
        sm.text().substring(SummaryPrefix.length)
    }.orNull
  }

  private def generateSummary(existingSummary: String, messagesToSummarize: List[ChatMessage]): String = {
    val conversationText = new StringBuilder()

    if (existingSummary != null) {
      conversationText.append("Previous summary:\n").append(existingSummary).append("\n\n")
      conversationText.append("New messages to incorporate:\n")
    }

    for (msg <- messagesToSummarize) {
      msg match {
        case um: UserMessage   => conversationText.append("User: ").append(um.singleText()).append("\n")
        case am: AiMessage     => conversationText.append("Assistant: ").append(am.text()).append("\n")
        case sm: SystemMessage => conversationText.append("System: ").append(sm.text()).append("\n")
        case _                 => // skip
      }
    }

    val prompt = SummarizationPrompt.format(conversationText.toString())

    try {
      summarizationModel.chat(prompt)
    } catch {
      case _: Exception =>
        if (existingSummary != null) {
          existingSummary + " [Additional context available]"
        } else {
          "[Conversation history available]"
        }
    }
  }

  private def countTokens(msgs: List[ChatMessage]): Int = {
    msgs.map(msg => tokenCountEstimator.estimateTokenCountInMessage(msg)).sum
  }
}

object SummarizingTokenWindowChatMemory {

  def builder(): Builder = new Builder()

  class Builder {
    private var _id: AnyRef = _
    private var _maxTokens: Int = 4000
    private var _tokenCountEstimator: TokenCountEstimator = _
    private var _summarizationModel: ChatModel = _
    private var _memoryStore: HistoryAwareChatMemoryStore = _

    def id(id: AnyRef): Builder = {
      _id = id
      this
    }

    def maxTokens(maxTokens: Int): Builder = {
      _maxTokens = maxTokens
      this
    }

    def tokenCountEstimator(estimator: TokenCountEstimator): Builder = {
      _tokenCountEstimator = estimator
      this
    }

    def chatModel(model: ChatModel): Builder = {
      _summarizationModel = model
      this
    }

    def chatMemoryStore(store: HistoryAwareChatMemoryStore): Builder = {
      _memoryStore = store
      this
    }

    def build(): SummarizingTokenWindowChatMemory = {
      require(_id != null, "id must be set")
      require(_tokenCountEstimator != null, "tokenCountEstimator must be set")
      require(_summarizationModel != null, "chatModel must be set")
      require(_memoryStore != null, "chatMemoryStore must be set")
      new SummarizingTokenWindowChatMemory(_id, _maxTokens, _tokenCountEstimator, _summarizationModel, _memoryStore)
    }
  }
}
