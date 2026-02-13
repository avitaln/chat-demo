package com.chatdemo.app

import com.chatdemo.common.config.ProviderConfig
import com.chatdemo.common.model.{ConversationMessage, MessageAttachment, MessageAttachmentExtractor}
import com.chatdemo.common.service.{ChatBackend, ChatResult, ImageModelStatus}

import java.util.{Locale, Scanner}
import scala.collection.mutable.ArrayBuffer

/**
 * Thin CLI client for the chat application.
 * Depends only on common interfaces - never imports backend.
 *
 * Model selection and pending attachments are kept client-side
 * and sent inline with each chat request (stateless server contract).
 */
class ChatApplication(private val backend: ChatBackend) {

  private val AnsiReset = "\u001B[0m"
  private val AnsiUser  = "\u001B[38;5;33m"
  private val AnsiModel = "\u001B[38;5;214m"
  private val AnsiDim   = "\u001B[2m"

  private var currentConversationId: String = _
  private var currentModelIndex: Int = 0
  private val pendingAttachments: ArrayBuffer[MessageAttachment] = ArrayBuffer.empty

  def run(): Unit = {
    val scanner = new Scanner(System.in)

    printWelcome()

    var running = true
    while (running) {
      val prompt = if (currentConversationId != null) {
        s"\n${AnsiUser}You${AnsiDim} [$currentConversationId]${AnsiReset}\n"
      } else {
        s"\n${AnsiUser}You${AnsiReset}\n"
      }
      print(prompt)
      val input = normalizeInput(scanner.nextLine())

      if (input.isEmpty) {
        // skip
      } else if (input.equalsIgnoreCase("/quit") || input.equalsIgnoreCase("/exit")) {
        println("Goodbye!")
        running = false
      } else if (input.equalsIgnoreCase("/setmodel")) {
        handleSetModel(scanner)
      } else if (input.equalsIgnoreCase("/clear")) {
        handleClear()
      } else if (input.equalsIgnoreCase("/help")) {
        printHelp()
      } else if (input.toLowerCase.startsWith("/create")) {
        handleCreate(input)
      } else if (input.toLowerCase.startsWith("/load")) {
        handleLoad(input)
      } else if (input.equalsIgnoreCase("/conversations")) {
        handleListConversations()
      } else if (input.toLowerCase.startsWith("/imagemodel")) {
        handleImageModel(input)
      } else if (input.toLowerCase.startsWith("/attach")) {
        handleAttachCommand(input)
      } else if (input.startsWith("/")) {
        println("Unknown command. Type /help for available commands.")
      } else {
        handleChat(input)
      }
    }

    scanner.close()
  }

  private def printWelcome(): Unit = {
    println("===========================================")
    println("   AI Chatbot CLI - LangChain4j Demo")
    println("===========================================")
    println()
    printHelp()
    println()
    val models = backend.getAvailableModels
    if (models.nonEmpty) {
      println("Current model: " + models(currentModelIndex).getDisplayName)
    }
    println()
    println("Use /create <id> to start a new conversation, or /load <id> to resume one.")
  }

  private def printHelp(): Unit = {
    println("Commands:")
    println("  /create <id>  - Create a new conversation")
    println("  /load <id>    - Load an existing conversation")
    println("  /conversations - List all conversations")
    println("  /setmodel     - Switch AI model")
    println("  /imagemodel   - Switch image model (openai|gemini|grok)")
    println("  /attach <url> - Stage attachment for next message")
    println("  /clear        - Clear current conversation history")
    println("  /help         - Show this help")
    println("  /quit         - Exit application")
  }

  // ----------------------------------------------------------------
  // Conversation commands
  // ----------------------------------------------------------------

  private def handleCreate(input: String): Unit = {
    val parts = input.trim.split("\\s+", 2)
    if (parts.length < 2 || parts(1).isBlank) {
      println("Usage: /create <conversation-id>")
      return
    }
    val id = parts(1).trim
    val created = backend.createConversation(id)
    currentConversationId = id
    if (created) {
      println("Created conversation: " + id)
    } else {
      println("Conversation already exists, switched to: " + id)
    }
  }

  private def handleLoad(input: String): Unit = {
    val parts = input.trim.split("\\s+", 2)
    if (parts.length < 2 || parts(1).isBlank) {
      println("Usage: /load <conversation-id>")
      return
    }
    val id = parts(1).trim
    val history = backend.getConversationHistory(id)
    currentConversationId = id
    if (history.isEmpty) {
      println(s"Loaded conversation: $id (empty - use /create to start fresh)")
    } else {
      println(s"Loaded conversation: $id (${history.size} messages)")
      val start = Math.max(0, history.size - 6)
      if (start > 0) {
        println(s"  ... ($start earlier messages)")
      }
      for (i <- start until history.size) {
        val msg = history(i)
        val role = messageRole(msg)
        val text = messageText(msg)
        val preview = if (text.length > 120) text.substring(0, 120) + "..." else text
        println(s"  [$role] $preview")
      }
    }
  }

  private def handleListConversations(): Unit = {
    val ids = backend.listConversations()
    if (ids.isEmpty) {
      println("No conversations yet. Use /create <id> to start one.")
    } else {
      println("Conversations:")
      for (id <- ids) {
        val marker = if (id == currentConversationId) " [active]" else ""
        println(s"  - $id$marker")
      }
    }
  }

  // ----------------------------------------------------------------
  // Model selection (client-side, sent per-request)
  // ----------------------------------------------------------------

  private def handleSetModel(scanner: Scanner): Unit = {
    val models = backend.getAvailableModels

    println("\nAvailable models:")
    for (i <- models.indices) {
      val marker = if (i == currentModelIndex) " [current]" else ""
      println(s"  ${i + 1}. ${models(i).getDisplayName}$marker")
    }

    print(s"\nSelect model (1-${models.size}): ")
    val choice = scanner.nextLine().trim

    try {
      val index = Integer.parseInt(choice) - 1
      if (index >= 0 && index < models.size) {
        currentModelIndex = index
        println("Switched to: " + models(index).getDisplayName)
        println("(Will be used on next chat request)")
      } else {
        println(s"Invalid selection. Please choose 1-${models.size}")
      }
    } catch {
      case _: NumberFormatException =>
        println("Invalid input. Please enter a number.")
    }
  }

  private def handleClear(): Unit = {
    if (currentConversationId == null) {
      println("No active conversation. Use /create or /load first.")
      return
    }
    backend.clearConversation(currentConversationId)
    println("Conversation history cleared. Starting fresh!")
  }

  private def handleChat(message: String): Unit = {
    if (currentConversationId == null) {
      println("No active conversation. Use /create <id> or /load <id> first.")
      return
    }

    val models = backend.getAvailableModels
    val providerName = if (models.nonEmpty && currentModelIndex < models.size) {
      models(currentModelIndex).getDisplayName.split(" ")(0)
    } else {
      "AI"
    }
    println()
    print(s"${AnsiModel}$providerName${AnsiReset}\n")

    // Consume pending attachments for this request
    val attachments = pendingAttachments.toList
    pendingAttachments.clear()

    val result = backend.chat(currentConversationId, message, attachments, currentModelIndex,
      new com.chatdemo.common.service.ChatStreamHandler {
        override def onToken(token: String): Unit = {
          print(token)
          System.out.flush()
        }
      })

    println()

    if (result.error != null) {
      println("[Error] " + result.error)
    }

    printAttachments(result.responseAttachments)
  }

  private def handleAttachCommand(input: String): Unit = {
    if (currentConversationId == null) {
      println("No active conversation. Use /create or /load first.")
      return
    }
    val parts = input.trim.split("\\s+", 2)
    if (parts.length < 2 || parts(1).isBlank) {
      println("Usage: /attach <URL>")
      return
    }
    val attachment = MessageAttachmentExtractor.fromUrl(parts(1).trim)
    if (attachment == null) {
      println("Could not parse URL for attachment.")
      return
    }
    pendingAttachments.append(attachment)
    println(s"Attachment staged: [${attachment.attachmentType}] ${attachment.url}")
  }

  private def handleImageModel(input: String): Unit = {
    val parts = input.trim.split("\\s+")
    if (parts.length == 1) {
      printImageModelStatus()
      return
    }

    val provider = parts(1).toLowerCase
    val modelName = if (parts.length >= 3) parts(2) else null

    provider match {
      case "openai" | "dall-e" | "dalle" =>
        val status = backend.setImageModel("openai", modelName)
        println(s"Image model set to OpenAI (${status.openAiModelName})")
      case "gemini" =>
        val status = backend.setImageModel("gemini", modelName)
        println(s"Image model set to Gemini (${status.geminiModelName})")
      case "grok" =>
        val status = backend.setImageModel("grok", modelName)
        println(s"Image model set to Grok (${status.grokModelName})")
      case _ =>
        val status = backend.getImageModelStatus
        println("Unknown image model provider. Use /imagemodel openai, /imagemodel gemini, or /imagemodel grok.")
        println("Examples:")
        println("  /imagemodel openai")
        println("  /imagemodel gemini")
        println("  /imagemodel grok")
        println(s"  /imagemodel gemini ${status.defaultGeminiModelName}")
    }
  }

  private def printImageModelStatus(): Unit = {
    val status = backend.getImageModelStatus
    println("Image model providers:")
    println(s"  OpenAI: ${status.openAiModelName}")
    println(s"  Gemini: ${status.geminiModelName}")
    println(s"  Grok: ${status.grokModelName}")
    println(s"Current image provider: ${status.currentProvider} (${status.currentModelName})")
    println("Examples:")
    println("  /imagemodel openai")
    println("  /imagemodel gemini")
    println("  /imagemodel grok")
    println(s"  /imagemodel gemini ${status.defaultGeminiModelName}")
  }

  // ----------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------

  private def printAttachments(attachments: List[MessageAttachment]): Unit = {
    if (attachments == null || attachments.isEmpty) {
      return
    }
    println("Attachments:")
    for (attachment <- attachments) {
      val t = if (attachment.attachmentType == null) "link" else attachment.attachmentType.toLowerCase(Locale.ROOT)
      val mime = if (attachment.mimeType == null) "" else s" (${attachment.mimeType})"
      println(s"- [$t] ${attachment.url}$mime")
    }
  }

  private def messageRole(msg: ConversationMessage): String = {
    msg.message match {
      case _: dev.langchain4j.data.message.UserMessage   => "you"
      case _: dev.langchain4j.data.message.AiMessage     => "ai"
      case _: dev.langchain4j.data.message.SystemMessage  => "system"
      case _ => "?"
    }
  }

  private def messageText(msg: ConversationMessage): String = {
    msg.message match {
      case u: dev.langchain4j.data.message.UserMessage   => u.singleText()
      case a: dev.langchain4j.data.message.AiMessage     => a.text()
      case s: dev.langchain4j.data.message.SystemMessage  => s.text()
      case other => other.toString
    }
  }

  private def normalizeInput(raw: String): String = {
    if (raw == null) {
      return ""
    }
    val stripped = raw.strip
    val dePasted = stripped
      .replace("\u001b[200~", "") // bracketed-paste prefix used by some terminals
      .replace("\u001b[201~", "") // bracketed-paste suffix used by some terminals
      .strip
    val cleaned = dePasted.dropWhile(ch => Character.getType(ch) == Character.FORMAT)
    if (cleaned.isEmpty) {
      ""
    } else if (cleaned.startsWith("／") || cleaned.startsWith("⁄") || cleaned.startsWith("∕") || cleaned.startsWith("⧸") || cleaned.startsWith("╱")) {
      "/" + cleaned.substring(1)
    } else {
      cleaned
    }
  }
}
