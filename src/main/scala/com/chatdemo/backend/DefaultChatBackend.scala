package com.chatdemo.backend

import com.chatdemo.backend.config.{FirebaseConfig, ModelsConfig}
import com.chatdemo.backend.document.{DocumentArtifactCache, DocumentContextService, GcsDocumentArtifactCache, NoopDocumentArtifactCache}
import com.chatdemo.backend.logging.LlmExchangeLogger
import com.chatdemo.backend.memory.{HistoryAwareChatMemoryStore, SummarizingTokenWindowChatMemory}
import com.chatdemo.backend.model.ModelFactory
import com.chatdemo.backend.repository.{ConversationRepository, SqliteConversationRepository}
import com.chatdemo.backend.storage.FirebaseStorageUploader
import com.chatdemo.backend.tools.ImageGenerationTool
import com.chatdemo.common.config.ProviderConfig
import com.chatdemo.common.model.{ConversationMessage, MessageAttachment, MessageAttachmentExtractor}
import com.chatdemo.common.service.{ChatBackend, ChatResult, ChatStreamHandler, ImageModelStatus}
import dev.langchain4j.data.message.{AiMessage, ChatMessage, ImageContent, TextContent, UserMessage}
import dev.langchain4j.memory.chat.ChatMemoryProvider
import dev.langchain4j.model.TokenCountEstimator
import dev.langchain4j.model.chat.{ChatModel, StreamingChatModel}
import dev.langchain4j.model.image.{DisabledImageModel, ImageModel}
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.service.{AiServices, MemoryId, TokenStream, V}
import dev.langchain4j.service.{SystemMessage => ServiceSystemMessage, UserMessage => ServiceUserMessage}

import java.io.{ByteArrayOutputStream, IOException, InputStream}
import java.net.{HttpURLConnection, URL}
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.regex.Pattern
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/**
 * Stateless backend implementation that wires LangChain4j models,
 * memory, document context, image generation, and logging.
 *
 * Each request is self-contained: the model and attachments are specified
 * inline. No cross-request in-memory state is held for chat context.
 * Conversation history is loaded from the repository per request.
 */
class DefaultChatBackend extends ChatBackend {

  private val MaxTokens = 4000
  private val NonPremiumImageDeniedMessage =
    "System: Image generation and image editing are available for premium users only."
  private val UrlPattern: Pattern = Pattern.compile("(https?://\\S+)")

  // --- Immutable / shared infrastructure (safe across requests) ---
  private val configs: List[ProviderConfig] = ModelsConfig.Providers
  private val conversationRepository: ConversationRepository = new SqliteConversationRepository(
    sys.env.getOrElse("CHAT_DB_PATH", "chat-demo.db")
  )
  private val memoryStore = new HistoryAwareChatMemoryStore(conversationRepository)
  private val tokenCountEstimator: TokenCountEstimator = new OpenAiTokenCountEstimator("gpt-4")
  private val imageTool = new ImageGenerationTool()
  private val documentContextService: DocumentContextService = createDocumentContextService()
  private val llmExchangeLogger = new LlmExchangeLogger(Path.of("logs", "llm-exchanges.log"))

  // --- Image model defaults (kept for image generation tool config) ---
  private val defaultOpenAiImageModel: String = ModelFactory.defaultOpenAiImageModel
  private val defaultGeminiImageModel: String = ModelFactory.defaultGeminiImageModel
  private val defaultGrokImageModel: String = ModelFactory.defaultGrokImageModel
  private var currentOpenAiImageModel: String = defaultOpenAiImageModel
  private var currentGeminiImageModel: String = defaultGeminiImageModel
  private var currentGrokImageModel: String = defaultGrokImageModel
  private var currentImageProvider: String = "openai"

  configureFirebaseStorage()
  refreshImageModels()

  /**
   * AI Assistant trait - LangChain4j will implement this via proxy.
   */
  trait Assistant {
    @ServiceSystemMessage(Array("{{systemPrompt}}"))
    def chat(
      @MemoryId memoryId: String,
      @V("systemPrompt") systemPrompt: String,
      @ServiceUserMessage message: String
    ): TokenStream
  }

  // ----------------------------------------------------------------
  // Conversation management
  // ----------------------------------------------------------------

  override def createConversation(conversationId: String): Boolean = {
    conversationRepository.createConversation(conversationId)
  }

  override def getConversationHistory(conversationId: String): List[ConversationMessage] = {
    conversationRepository.getFullHistory(conversationId)
  }

  override def listConversations(): List[String] = {
    conversationRepository.listConversationIds()
  }

  // ----------------------------------------------------------------
  // Model management (read-only)
  // ----------------------------------------------------------------

  override def getAvailableModels: List[ProviderConfig] = configs

  // ----------------------------------------------------------------
  // Chat (stateless per request)
  // ----------------------------------------------------------------

  override def chat(
    conversationId: String,
    message: String,
    attachments: List[MessageAttachment],
    modelIndex: Int,
    isPremium: Boolean,
    streamHandler: ChatStreamHandler
  ): ChatResult = {
    conversationRepository.createConversation(conversationId)

    val safeModelIndex = if (modelIndex >= 0 && modelIndex < configs.size) modelIndex else 0
    val currentConfig = configs(safeModelIndex)
    val providerName = currentConfig.getDisplayName.split(" ")(0)
    val modelName = currentConfig.model

    if (shouldRejectImageRequestForNonPremium(isPremium, conversationId, message, attachments)) {
      val denied = denyNonPremiumImageRequest(conversationId, message, attachments, streamHandler)
      llmExchangeLogger.logExchange(
        providerName, modelName, message, message,
        List("[system] " + NonPremiumImageDeniedMessage, "[user] " + safeMessage(message)),
        NonPremiumImageDeniedMessage, Nil, null
      )
      return denied
    }

    val systemPrompt = getSystemPromptForRequest(conversationId, message)
    val messageForModel = enrichMessageForModel(conversationId, message, attachments)
    val effectivePayload = buildEffectivePayload(conversationId, systemPrompt, messageForModel)

    if (shouldUseDirectVisionAnswer(currentConfig, message, attachments)) {
      tryDirectVisionAnswer(conversationId, message, attachments, safeModelIndex, streamHandler, providerName, modelName) match {
        case Some(result) =>
          llmExchangeLogger.logExchange(
            providerName, modelName, message, "[multimodal] " + message,
            effectivePayload, safeVisionResponseText(result),
            result.responseAttachments, result.error
          )
          return result
        case None => // fall through to normal streaming path
      }
    }

    // Build assistant per-request (no cached state) — always register image tools;
    // the LLM and system prompt decide when to use them.
    val assistant = createAssistant(currentConfig, includeImageTools = true)

    val done = new CountDownLatch(1)
    val responseBuffer = new StringBuilder()
    val errorHolder = new Array[String](1)

    try {
      val stream = assistant.chat(conversationId, systemPrompt, messageForModel)
      stream.onPartialResponse(token => {
        responseBuffer.append(token)
        streamHandler.onToken(token)
      }).onCompleteResponse(_ => {
        done.countDown()
      }).onError(error => {
        errorHolder(0) = error.getMessage
        done.countDown()
      }).start()
      done.await()

      // Persist user attachments
      if (attachments.nonEmpty) {
        conversationRepository.attachToLatestUserMessage(conversationId, attachments)
      }
      val toolAttachments = consumeToolAttachments(conversationId)
      val latestAttachments =
        if (toolAttachments.nonEmpty) toolAttachments
        else getLatestAiAttachments(conversationId)
      llmExchangeLogger.logExchange(
        providerName, modelName, message, messageForModel,
        effectivePayload, responseBuffer.toString(),
        latestAttachments, errorHolder(0)
      )
      ChatResult(latestAttachments, errorHolder(0))
    } catch {
      case ie: InterruptedException =>
        Thread.currentThread().interrupt()
        val error = "Interrupted while waiting for response."
        llmExchangeLogger.logExchange(
          providerName, modelName, message, messageForModel,
          effectivePayload, responseBuffer.toString(),
          getLatestAiAttachments(conversationId), error
        )
        ChatResult(Nil, error)
      case e: Exception =>
        val error = e.getMessage
        llmExchangeLogger.logExchange(
          providerName, modelName, message, messageForModel,
          effectivePayload, responseBuffer.toString(),
          getLatestAiAttachments(conversationId), error
        )
        ChatResult(Nil, error)
    }
  }

  override def clearConversation(conversationId: String): Unit = {
    conversationRepository.clear(conversationId)
  }

  // ----------------------------------------------------------------
  // Image model
  // ----------------------------------------------------------------

  override def getImageModelStatus: ImageModelStatus = {
    ImageModelStatus(
      currentImageProvider,
      imageTool.getCurrentModelName,
      currentOpenAiImageModel,
      currentGeminiImageModel,
      currentGrokImageModel,
      defaultGeminiImageModel
    )
  }

  override def setImageModel(provider: String, modelName: String): ImageModelStatus = {
    provider match {
      case "openai" =>
        if (modelName != null && !modelName.isBlank) {
          currentOpenAiImageModel = modelName
        }
        currentImageProvider = "openai"
        imageTool.setProvider(ImageGenerationTool.Provider.OPENAI)
      case "gemini" =>
        if (modelName != null && !modelName.isBlank) {
          currentGeminiImageModel = modelName
        }
        currentImageProvider = "gemini"
        imageTool.setProvider(ImageGenerationTool.Provider.GEMINI)
      case "grok" =>
        if (modelName != null && !modelName.isBlank) {
          currentGrokImageModel = modelName
        }
        currentImageProvider = "grok"
        imageTool.setProvider(ImageGenerationTool.Provider.GROK)
      case _ => // ignore unknown
    }
    refreshImageModels()
    getImageModelStatus
  }

  def getLogPath: String = llmExchangeLogger.getLogPath.toAbsolutePath.toString

  // ----------------------------------------------------------------
  // Internal wiring (per-request assistant creation)
  // ----------------------------------------------------------------

  private def createAssistant(config: ProviderConfig, includeImageTools: Boolean): Assistant = {
    val model: ChatModel = ModelFactory.createModel(config)
    val streamingModel: StreamingChatModel = ModelFactory.createStreamingModel(config)
    syncImageProviderWithChatModel(config)
    if ("claude".equalsIgnoreCase(config.providerType)) {
      imageTool.disableImageTools("Image generation is not available when using Anthropic.")
    } else {
      imageTool.enableImageTools()
    }

    val memoryProvider: ChatMemoryProvider = (memoryId: AnyRef) => {
      SummarizingTokenWindowChatMemory.builder()
        .id(memoryId)
        .maxTokens(MaxTokens)
        .tokenCountEstimator(tokenCountEstimator)
        .chatMemoryStore(memoryStore)
        .chatModel(model)
        .build()
    }

    val builder = AiServices.builder(classOf[Assistant])
      .streamingChatModel(streamingModel)
      .chatMemoryProvider(memoryProvider)
    if (includeImageTools) {
      builder.tools(imageTool)
    }
    builder.build()
  }

  private def syncImageProviderWithChatModel(config: ProviderConfig): Unit = {
    config.providerType.toLowerCase match {
      case "gemini" =>
        currentImageProvider = "gemini"
        imageTool.setProvider(ImageGenerationTool.Provider.GEMINI)
      case "chatgpt" =>
        currentImageProvider = "openai"
        imageTool.setProvider(ImageGenerationTool.Provider.OPENAI)
      case "grok" =>
        currentImageProvider = "grok"
        imageTool.setProvider(ImageGenerationTool.Provider.GROK)
      case _ => // keep current
    }
  }

  private def refreshImageModels(): Unit = {
    val openAiKey = ModelsConfig.getOpenAiKey
    val openAiModel: ImageModel = if (openAiKey == null || openAiKey.isBlank) {
      new DisabledImageModel()
    } else {
      ModelFactory.createImageModel(openAiKey, currentOpenAiImageModel)
    }

    val geminiKey = ModelsConfig.getGeminiKey
    val geminiModel: ChatModel = if (geminiKey == null || geminiKey.isBlank) {
      null
    } else {
      ModelFactory.createGeminiImageModel(geminiKey, currentGeminiImageModel)
    }

    val grokKey = ModelsConfig.getGrokKey
    val grokModel: ImageModel = if (grokKey == null || grokKey.isBlank) {
      new DisabledImageModel()
    } else {
      ModelFactory.createGrokImageModel(grokKey, currentGrokImageModel)
    }

    imageTool.setOpenAiImageModel(openAiModel, currentOpenAiImageModel)
    imageTool.setGeminiImageModel(geminiModel, currentGeminiImageModel)
    imageTool.setGrokImageModel(grokModel, currentGrokImageModel)
  }

  private def configureFirebaseStorage(): Unit = {
    val uploader = new FirebaseStorageUploader(
      FirebaseConfig.getServiceAccountPath,
      FirebaseConfig.getStorageBucket
    )
    if (uploader.isConfigured) {
      imageTool.setFirebaseStorageUploader(uploader)
    }
  }

  private def createDocumentContextService(): DocumentContextService = {
    val gcsCache = new GcsDocumentArtifactCache(
      FirebaseConfig.getServiceAccountPath,
      FirebaseConfig.getStorageBucket
    )
    val cache: DocumentArtifactCache = if (gcsCache.isConfigured) gcsCache else new NoopDocumentArtifactCache()
    new DocumentContextService(cache)
  }

  // ----------------------------------------------------------------
  // Message enrichment and attachment handling
  // ----------------------------------------------------------------

  private def enrichMessageForModel(conversationId: String, message: String, attachments: List[MessageAttachment]): String = {
    val enriched = enrichMessageWithLatestImageAttachment(conversationId, message, attachments)
    enrichMessageWithLatestDocumentContext(conversationId, enriched, message, attachments)
  }

  private def enrichMessageWithLatestImageAttachment(conversationId: String, message: String, attachments: List[MessageAttachment]): String = {
    if (extractFirstUrl(message) != null) {
      return message
    }
    val latestImageUrl = findLatestImageAttachmentUrl(conversationId, attachments)
    if (latestImageUrl == null) {
      return message
    }
    val shouldInjectImageUrl =
      containsAttachmentType(attachments, "image") ||
        referencesImage(message) ||
        isImageEditRequest(message) ||
        isImageFollowUpRequest(message)
    if (!shouldInjectImageUrl) {
      return message
    }
    message + "\n\nSource image URL: " + latestImageUrl
  }

  private def enrichMessageWithLatestDocumentContext(
    conversationId: String,
    messageForModel: String,
    originalMessage: String,
    attachments: List[MessageAttachment]
  ): String = {
    val documentUrl = findLatestDocumentAttachmentUrl(conversationId, originalMessage, attachments)
    if (documentUrl == null) {
      return messageForModel
    }
    if (!documentContextService.isLikelyDocumentQuestion(originalMessage) &&
      !containsAttachmentType(attachments, "document")) {
      return messageForModel
    }
    documentContextService.buildContext(documentUrl, originalMessage) match {
      case Some(context) =>
        messageForModel + "\n\nDocument source URL: " + documentUrl +
          "\n\nDocument context snippets:\n" + context +
          "\n\nUse the provided document context to answer accurately."
      case None => messageForModel
    }
  }

  private def findLatestImageAttachmentUrl(conversationId: String, attachments: List[MessageAttachment]): String = {
    val pendingImage = findLatestAttachmentByType(attachments, "image")
    if (pendingImage != null && pendingImage.url != null && !pendingImage.url.isBlank) {
      return pendingImage.url
    }
    val history = conversationRepository.getFullHistory(conversationId)
    var i = history.size - 1
    while (i >= 0) {
      val message = history(i)
      val msgAttachments = message.attachments
      if (msgAttachments != null && msgAttachments.nonEmpty) {
        var j = msgAttachments.size - 1
        while (j >= 0) {
          val attachment = msgAttachments(j)
          if ("image".equalsIgnoreCase(attachment.attachmentType) && attachment.url != null && !attachment.url.isBlank) {
            return attachment.url
          }
          j -= 1
        }
      }
      i -= 1
    }
    null
  }

  private def findLatestDocumentAttachmentUrl(conversationId: String, currentMessage: String, attachments: List[MessageAttachment]): String = {
    val pendingDoc = findLatestAttachmentByType(attachments, "document")
    if (pendingDoc != null && pendingDoc.url != null && !pendingDoc.url.isBlank) {
      return pendingDoc.url
    }
    val inCurrentMessage = MessageAttachmentExtractor.extractFromText(currentMessage)
    val currentDoc = findLatestAttachmentByType(inCurrentMessage, "document")
    if (currentDoc != null && currentDoc.url != null && !currentDoc.url.isBlank) {
      return currentDoc.url
    }
    val history = conversationRepository.getFullHistory(conversationId)
    var i = history.size - 1
    while (i >= 0) {
      val msg = history(i)
      val attachment = findLatestAttachmentByType(msg.attachments, "document")
      if (attachment != null && attachment.url != null && !attachment.url.isBlank) {
        return attachment.url
      }
      i -= 1
    }
    null
  }

  private def findLatestAttachmentByType(attachments: List[MessageAttachment], attachmentType: String): MessageAttachment = {
    if (attachments == null || attachments.isEmpty) {
      return null
    }
    var i = attachments.size - 1
    while (i >= 0) {
      val attachment = attachments(i)
      if (attachment != null && attachmentType.equalsIgnoreCase(attachment.attachmentType)) {
        return attachment
      }
      i -= 1
    }
    null
  }

  private def containsAttachmentType(attachments: List[MessageAttachment], attachmentType: String): Boolean = {
    findLatestAttachmentByType(attachments, attachmentType) != null
  }

  private def consumeToolAttachments(conversationId: String): List[MessageAttachment] = {
    val toolAttachments = imageTool.consumePendingAttachments()
    if (toolAttachments.isEmpty) {
      return Nil
    }
    conversationRepository.attachToLatestAiMessage(conversationId, toolAttachments)
    val persistedOnAi = getLatestAiAttachments(conversationId).exists { existing =>
      existing != null && toolAttachments.exists(a => a != null && a.url == existing.url)
    }
    if (!persistedOnAi) {
      conversationRepository.attachToLatestUserMessage(conversationId, toolAttachments)
    }
    toolAttachments
  }

  private def getLatestAiAttachments(conversationId: String): List[MessageAttachment] = {
    val history = conversationRepository.getFullHistory(conversationId)
    if (history.isEmpty) {
      return Nil
    }
    val latest = history.last
    if (!latest.message.isInstanceOf[AiMessage]) {
      return Nil
    }
    if (latest.attachments == null) Nil else latest.attachments
  }

  // ----------------------------------------------------------------
  // Payload building and utilities
  // ----------------------------------------------------------------

  private def buildEffectivePayload(conversationId: String, systemPrompt: String, currentUserMessage: String): List[String] = {
    val payload = ArrayBuffer.empty[String]
    if (systemPrompt != null && !systemPrompt.isBlank) {
      payload.append("[system] " + safeMessage(systemPrompt))
    }
    val memoryMessages = memoryStore.getMessages(conversationId).asScala
    for (message <- memoryMessages) {
      payload.append(formatPayloadMessage(message))
    }
    payload.append("[user] " + safeMessage(currentUserMessage))
    payload.toList
  }

  private def getSystemPromptForRequest(memoryId: String, userMessage: String): String =
    "You are a helpful assistant. Respond clearly and concisely. " +
      "If the user asks to create, draw, generate, edit, or modify images, you MUST call the generate_image tool. " +
      "IMPORTANT: Do NOT attempt to generate or create images yourself. You MUST use the generate_image tool for ALL image requests. " +
      "When the user wants to modify a previously generated image, call generate_image with a complete prompt that describes the full desired result including the requested changes. " +
      "For normal knowledge and reasoning questions, answer directly without image tools. " +
      "Return tool outputs directly when they satisfy the request."

  private def formatPayloadMessage(message: ChatMessage): String = {
    message match {
      case um: dev.langchain4j.data.message.UserMessage =>
        "[memory.user] " + safeMessage(um.singleText())
      case am: AiMessage =>
        "[memory.ai] " + safeMessage(am.text())
      case sm: dev.langchain4j.data.message.SystemMessage =>
        "[memory.system] " + safeMessage(sm.text())
      case other =>
        "[memory.other] " + safeMessage(other.toString)
    }
  }

  private def safeMessage(text: String): String = {
    if (text == null || text.isBlank) "(empty)" else text
  }

  private def shouldUseDirectVisionAnswer(config: ProviderConfig, message: String, attachments: List[MessageAttachment]): Boolean = {
    if (!supportsDirectVision(config.providerType)) {
      return false
    }
    if (!containsAttachmentType(attachments, "image")) {
      return false
    }
    !isImageEditRequest(message) && !isImageGenerationRequest(message)
  }

  private def supportsDirectVision(providerType: String): Boolean = {
    if (providerType == null) {
      return false
    }
    providerType.equalsIgnoreCase("gemini") ||
      providerType.equalsIgnoreCase("chatgpt") ||
      providerType.equalsIgnoreCase("grok")
  }

  private def tryDirectVisionAnswer(
    conversationId: String,
    message: String,
    attachments: List[MessageAttachment],
    modelIndex: Int,
    streamHandler: ChatStreamHandler,
    providerName: String,
    modelName: String
  ): Option[ChatResult] = {
    val image = findLatestAttachmentByType(attachments, "image")
    if (image == null || image.url == null || image.url.isBlank) {
      return None
    }
    try {
      val payload = downloadImage(image.url, image.mimeType)
      val base64 = Base64.getEncoder.encodeToString(payload.bytes)
      val prompt = buildVisionPrompt(message)
      val userMessage = UserMessage.from(
        ImageContent.from(base64, payload.mimeType),
        TextContent.from(prompt)
      )
      val response: ChatResponse = ModelFactory.createModel(configs(modelIndex)).chat(userMessage)
      val aiText = Option(response.aiMessage()).map(_.text()).getOrElse("")

      conversationRepository.addMessage(conversationId, UserMessage.from(message))
      if (attachments.nonEmpty) {
        conversationRepository.attachToLatestUserMessage(conversationId, attachments)
      }
      conversationRepository.addMessage(conversationId, AiMessage.from(aiText))

      streamHandler.onToken(aiText)
      Some(ChatResult(Nil, null))
    } catch {
      case _: Exception => None
    }
  }

  private def buildVisionPrompt(message: String): String = {
    "Analyze the attached image and answer the user request based on visual content.\n\nUser request: " + message
  }

  private def safeVisionResponseText(result: ChatResult): String = {
    if (result == null) {
      ""
    } else if (result.error != null) {
      ""
    } else {
      "(multimodal response)"
    }
  }

  private def isImageFollowUpRequest(message: String): Boolean = {
    val lower = message.toLowerCase
    lower.contains("try again") ||
      lower.contains("again") ||
      lower.contains("retry") ||
      lower.contains("another version") ||
      lower.contains("same image") ||
      lower.contains("this image") ||
      lower.contains("that image") ||
      lower.contains("instead")
  }

  private def isImageEditRequest(message: String): Boolean = {
    val lower = message.toLowerCase
    lower.contains("edit") ||
      lower.contains("modify") ||
      lower.contains("change") ||
      lower.contains("transform") ||
      lower.contains("add") ||
      lower.contains("remove") ||
      lower.contains("replace")
  }

  private def isImageGenerationRequest(message: String): Boolean = {
    val lower = message.toLowerCase
    val actionVerb =
      lower.contains("generate") ||
        lower.contains("create") ||
        lower.contains("draw") ||
        lower.contains("sketch")
    val imageNoun =
      lower.contains("image") ||
        lower.contains("photo") ||
        lower.contains("picture") ||
        lower.contains("illustration") ||
        lower.contains("logo")
    actionVerb && imageNoun
  }

  private def shouldRejectImageRequestForNonPremium(
    isPremium: Boolean,
    conversationId: String,
    message: String,
    attachments: List[MessageAttachment]
  ): Boolean = {
    if (isPremium) {
      return false
    }
    if (isImageGenerationRequest(message)) {
      return true
    }
    isImageEditRequest(message) &&
      (referencesImage(message) ||
        containsAttachmentType(attachments, "image") ||
        findLatestImageAttachmentUrl(conversationId, attachments) != null)
  }

  private def denyNonPremiumImageRequest(
    conversationId: String,
    message: String,
    attachments: List[MessageAttachment],
    streamHandler: ChatStreamHandler
  ): ChatResult = {
    conversationRepository.addMessage(conversationId, UserMessage.from(message))
    if (attachments.nonEmpty) {
      conversationRepository.attachToLatestUserMessage(conversationId, attachments)
    }
    conversationRepository.addMessage(conversationId, AiMessage.from(NonPremiumImageDeniedMessage))
    streamHandler.onToken(NonPremiumImageDeniedMessage)
    ChatResult(Nil, null)
  }

  private def referencesImage(message: String): Boolean = {
    val lower = message.toLowerCase
    lower.contains("image") ||
      lower.contains("photo") ||
      lower.contains("picture") ||
      lower.contains("screenshot")
  }

  private def extractFirstUrl(message: String): String = {
    val matcher = UrlPattern.matcher(message)
    if (matcher.find()) matcher.group(1) else null
  }

  @throws[IOException]
  private def downloadImage(imageUrl: String, preferredMimeType: String): ImagePayload = {
    val url = new URL(imageUrl)
    val connection = url.openConnection().asInstanceOf[HttpURLConnection]
    connection.setInstanceFollowRedirects(true)
    connection.setRequestProperty("User-Agent", "chat-demo")
    val status = connection.getResponseCode
    val inputStream = if (status >= 200 && status < 300) connection.getInputStream else connection.getErrorStream
    if (inputStream == null) {
      throw new IOException("Unable to download image: HTTP " + status)
    }
    val bytes = readAllBytes(inputStream)
    val resolvedMime = resolveMimeType(connection.getContentType, preferredMimeType, imageUrl)
    ImagePayload(bytes, resolvedMime)
  }

  private def readAllBytes(inputStream: InputStream): Array[Byte] = {
    try {
      val buffer = new ByteArrayOutputStream()
      val chunk = new Array[Byte](8192)
      var read = inputStream.read(chunk)
      while (read != -1) {
        buffer.write(chunk, 0, read)
        read = inputStream.read(chunk)
      }
      buffer.toByteArray
    } finally {
      inputStream.close()
    }
  }

  private def resolveMimeType(contentType: String, preferredMimeType: String, imageUrl: String): String = {
    if (preferredMimeType != null && !preferredMimeType.isBlank) {
      return preferredMimeType
    }
    if (contentType != null && !contentType.isBlank) {
      val separator = contentType.indexOf(';')
      return if (separator > 0) contentType.substring(0, separator).trim else contentType.trim
    }
    val lower = imageUrl.toLowerCase
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) "image/jpeg"
    else if (lower.endsWith(".gif")) "image/gif"
    else if (lower.endsWith(".webp")) "image/webp"
    else if (lower.endsWith(".bmp")) "image/bmp"
    else "image/png"
  }

  private case class ImagePayload(bytes: Array[Byte], mimeType: String)
}
