package com.chatdemo.common.model

import dev.langchain4j.data.message.ChatMessage
import java.time.Instant
import java.util.UUID

/**
 * Represents a single message in a conversation with metadata for history tracking.
 */
class ConversationMessage(
  val id: String,
  val message: ChatMessage,
  var attachments: List[MessageAttachment],
  var archived: Boolean,
  val createdAt: Instant
) {

  def this(message: ChatMessage) = {
    this(
      UUID.randomUUID().toString,
      message,
      Nil,
      false,
      Instant.now()
    )
  }

  def this(message: ChatMessage, attachments: List[MessageAttachment]) = {
    this(
      UUID.randomUUID().toString,
      message,
      if (attachments == null) Nil else attachments,
      false,
      Instant.now()
    )
  }
}
