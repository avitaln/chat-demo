package com.chatdemo.app

import com.chatdemo.common.model.{Conversation, ConversationMessage, MessageAttachment, MessageAttachmentExtractor, UserContext}
import com.chatdemo.common.service.ChatBackend

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

  private val FreeUserContext = UserContext(isPremium = "false", deviceId = "device-free-001", signedInId = None)
  private val PremiumAppleUserContext = UserContext(isPremium = "true", deviceId = "device-premium-001", signedInId = Some("apple-user-001"))

  private var currentConversationId: String = _
  private var currentModelId: String = _
  private var currentUserContext: UserContext = FreeUserContext
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
      } else if (input.equalsIgnoreCase("/c") || input.toLowerCase(Locale.ROOT).startsWith("/c ")) {
        handleConversationCommand(input)
      } else if (input.equalsIgnoreCase("/dump")) {
        handleDump()
      } else if (input.toLowerCase.startsWith("/attach")) {
        handleAttachCommand(input)
      } else if (input.toLowerCase.startsWith("/premium")) {
        handlePremium(input)
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
      if (currentModelId == null || !models.exists(_.modelId == currentModelId)) {
        currentModelId = models.head.modelId
      }
      println("Current model: " + models.find(_.modelId == currentModelId).map(_.getDisplayName).getOrElse(models.head.getDisplayName))
    }
    println()
    println("Use /c <id> to create or load a conversation, or /c to list all conversations.")
  }

  private def printHelp(): Unit = {
    println("Commands:")
    println("  /c <id>       - Create or load a conversation")
    println("  /c            - List all conversations")
    println("  /dump         - Dump current conversation messages")
    println("  /setmodel     - Switch AI model")
    println("  /premium      - Set premium mode (on|off)")
    println("  /attach <url> - Stage attachment for next message")
    println("  /clear        - Clear current conversation history")
    println("  /help         - Show this help")
    println("  /quit         - Exit application")
  }

  // ----------------------------------------------------------------
  // Conversation commands
  // ----------------------------------------------------------------

  private def handleConversationCommand(input: String): Unit = {
    val parts = input.trim.split("\\s+", 2)
    if (parts.length < 2 || parts(1).isBlank) {
      handleListConversations()
      return
    }
    val id = parts(1).trim
    val created = backend.createConversation(currentUserContext, id)
    currentConversationId = id
    if (created) {
      println("Created conversation: " + id)
    } else {
      val history = backend.getConversationHistory(currentUserContext, id)
      printConversationPreview(id, history)
    }
  }

  private def printConversationPreview(id: String, history: List[ConversationMessage]): Unit = {
    if (history.isEmpty) {
      println(s"Loaded conversation: $id (empty)")
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
    val conversations = backend.listConversations(currentUserContext)
    if (conversations.isEmpty) {
      println("No conversations yet. Use /c <id> to start one.")
    } else {
      println("Conversations:")
      for (conversation <- conversations) {
        printConversationListItem(conversation)
      }
    }
  }

  private def handleDump(): Unit = {
    if (currentConversationId == null) {
      println("No active conversation. Use /c <id> first.")
      return
    }
    val history = backend.getConversationHistory(currentUserContext, currentConversationId)
    if (history.isEmpty) {
      println(s"Conversation '$currentConversationId' has no messages.")
      return
    }
    println(s"Dumping conversation: $currentConversationId (${history.size} messages)")
    for (msg <- history) {
      val role = messageRole(msg)
      val text = messageText(msg)
      println(s"[$role] $text")
      if (msg.attachments != null && msg.attachments.nonEmpty) {
        for (attachment <- msg.attachments) {
          val t = if (attachment.attachmentType == null) "link" else attachment.attachmentType.toLowerCase(Locale.ROOT)
          val mime = if (attachment.mimeType == null) "" else s" (${attachment.mimeType})"
          println(s"  -> attachment [$t] ${attachment.url}$mime")
        }
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
      val marker = if (models(i).modelId == currentModelId) " [current]" else ""
      println(s"  ${i + 1}. ${models(i).getDisplayName}$marker")
    }

    print(s"\nSelect model (1-${models.size}): ")
    val choice = scanner.nextLine().trim

    try {
      val index = Integer.parseInt(choice) - 1
      if (index >= 0 && index < models.size) {
        currentModelId = models(index).modelId
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
      println("No active conversation. Use /c <id> first.")
      return
    }
    backend.clearConversation(currentUserContext, currentConversationId)
    println("Conversation history cleared. Starting fresh!")
  }

  private def handleChat(message: String): Unit = {
    if (currentConversationId == null) {
      println("No active conversation. Use /c <id> first.")
      return
    }

    val models = backend.getAvailableModels
    if (models.nonEmpty && (currentModelId == null || !models.exists(_.modelId == currentModelId))) {
      currentModelId = models.head.modelId
    }
    val providerName = if (models.nonEmpty) {
      models.find(_.modelId == currentModelId).map(_.getDisplayName.split(" ")(0)).getOrElse("AI")
    } else {
      "AI"
    }
    println()
    print(s"${AnsiModel}$providerName${AnsiReset}\n")

    // Consume pending attachments for this request
    val attachments = pendingAttachments.toList
    pendingAttachments.clear()

    val resolvedModelId = if (currentModelId == null || currentModelId.isBlank) {
      models.headOption.map(_.modelId).getOrElse("")
    } else {
      currentModelId
    }
    val result = backend.chat(currentConversationId, message, attachments, resolvedModelId, None, currentUserContext,
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
      println("No active conversation. Use /c <id> first.")
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

  private def handlePremium(input: String): Unit = {
    val parts = input.trim.split("\\s+")
    if (parts.length == 1) {
      val status = if (currentUserContext.isPremium.toBoolean) "on (premium apple)" else "off (free)"
      println(s"Premium mode is currently: $status")
      println("Usage: /premium on|off")
      return
    }
    parts(1).toLowerCase match {
      case "on" | "true" | "1" =>
        currentUserContext = PremiumAppleUserContext
        println(s"Switched to premium apple user (${currentUserContext.effectiveId}).")
      case "off" | "false" | "0" =>
        currentUserContext = FreeUserContext
        println(s"Switched to free user (${currentUserContext.effectiveId}).")
      case _ =>
        println("Usage: /premium on|off")
    }
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

  private def printConversationListItem(conversation: Conversation): Unit = {
    val marker = if (conversation.id == currentConversationId) " [active]" else ""
    val titlePart =
      if (conversation.title == null || conversation.title.isBlank || conversation.title == conversation.id) ""
      else s" - ${conversation.title}"
    println(s"  - ${conversation.id}$titlePart$marker")
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
