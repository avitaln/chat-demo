package com.chatdemo.backend.logging

import com.chatdemo.common.model.MessageAttachment

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Instant
import java.util.UUID

/**
 * Writes structured LLM request/response logs to a file.
 */
class LlmExchangeLogger(val logPath: Path) {

  private val lock = new AnyRef()

  ensureLogFile()
  appendSessionHeader()

  def getLogPath: Path = logPath

  def logExchange(
    providerName: String,
    modelName: String,
    rawUserInput: String,
    requestToModel: String,
    effectivePayload: List[String],
    modelResponse: String,
    responseAttachments: List[MessageAttachment],
    error: String
  ): Unit = {
    val id = UUID.randomUUID().toString
    val timestamp = Instant.now().toString
    val entry = new StringBuilder()
    entry.append("============================================================\n")
    entry.append("Exchange ID: ").append(id).append('\n')
    entry.append("Timestamp: ").append(timestamp).append('\n')
    entry.append("Provider: ").append(providerName).append('\n')
    entry.append("Model: ").append(modelName).append("\n\n")
    entry.append("------------ REQUEST START ------------\n")

    entry.append("[REQUEST][user_input]\n")
    entry.append(value(rawUserInput)).append("\n\n")

    entry.append("[REQUEST][sent_to_llm]\n")
    entry.append(value(requestToModel)).append("\n\n")

    entry.append("[REQUEST][effective_payload_messages]\n")
    if (effectivePayload == null || effectivePayload.isEmpty) {
      entry.append("(empty)\n\n")
    } else {
      for (payloadLine <- effectivePayload) {
        entry.append(payloadLine).append('\n')
      }
      entry.append('\n')
    }
    entry.append("------------- REQUEST END -------------\n\n")

    entry.append("------------ RESPONSE START -----------\n")
    entry.append("[RESPONSE][llm_output]\n")
    entry.append(value(modelResponse)).append("\n\n")

    entry.append("[RESPONSE][attachments]\n")
    if (responseAttachments == null || responseAttachments.isEmpty) {
      entry.append("(none)\n\n")
    } else {
      for (attachment <- responseAttachments) {
        val t = if (attachment.attachmentType == null) "link" else attachment.attachmentType
        val mime = if (attachment.mimeType == null) "" else s" (${attachment.mimeType})"
        entry.append(s"- [$t] ${attachment.url}$mime\n")
      }
      entry.append('\n')
    }

    if (error != null && !error.isBlank) {
      entry.append("[ERROR]\n").append(error).append('\n')
    } else {
      entry.append("[ERROR]\n(none)\n")
    }
    entry.append("------------- RESPONSE END ------------\n")
    entry.append('\n')

    append(entry.toString())
  }

  private def value(text: String): String = {
    if (text == null || text.isBlank) "(empty)" else text
  }

  private def ensureLogFile(): Unit = {
    try {
      val parent = logPath.getParent
      if (parent != null) {
        Files.createDirectories(parent)
      }
      if (!Files.exists(logPath)) {
        Files.createFile(logPath)
      }
    } catch {
      case e: IOException =>
        throw new RuntimeException("Failed to initialize log file: " + logPath, e)
    }
  }

  private def appendSessionHeader(): Unit = {
    val header = s"\n\n################ LLM SESSION ${Instant.now()} ################\n\n"
    append(header)
  }

  private def append(text: String): Unit = {
    lock.synchronized {
      try {
        Files.writeString(
          logPath,
          text,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
        )
      } catch {
        case e: IOException =>
          throw new RuntimeException("Failed to write LLM log file: " + logPath, e)
      }
    }
  }
}
