package com.chatdemo.backend

import com.chatdemo.backend.config.{FirebaseConfig, FirebaseInitializer, ModelsConfig}
import com.chatdemo.backend.document.{DocumentArtifactCache, DocumentContextService, GcsDocumentArtifactCache, NoopDocumentArtifactCache}
import com.chatdemo.backend.logging.LlmExchangeLogger
import com.chatdemo.backend.memory.{HistoryAwareChatMemoryStore, SummarizingTokenWindowChatMemory}
import com.chatdemo.backend.model.ModelFactory
import com.chatdemo.backend.provider.{AgentsProvider, HardcodedAgentsProvider, HardcodedModelsProvider, ModelsProvider}
import com.chatdemo.backend.repository.{ConversationRepository, FirestoreConversationRepository}
import com.chatdemo.backend.storage.FirebaseStorageUploader
import com.chatdemo.backend.tools.{GeminiImageGenerationTool, GrokImageGenerationTool, ImageGenerationTool, OpenAiImageGenerationTool, QwenImageGenerationTool}
import com.chatdemo.common.config.ProviderConfig
import com.chatdemo.common.model.{Conversation, ConversationMessage, MessageAttachment, MessageAttachmentExtractor, UserContext}
import com.chatdemo.common.service.{ChatBackend, ChatResult, ChatStreamHandler, ExposedImageModel, ImageModelAccessPolicy}
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
class DefaultChatBackend extends ChatBackend with ImageModelAccessPolicy {

  private val MaxTokens = 4000
  private val UrlPattern: Pattern = Pattern.compile("(https?://\\S+)")

  // --- Immutable / shared infrastructure (safe across requests) ---
  private val modelsProvider: ModelsProvider = new HardcodedModelsProvider()
  private val agentsProvider: AgentsProvider = new HardcodedAgentsProvider()
  private val configs: List[ProviderConfig] = modelsProvider.getAvailableModels
  private val agentsById: Map[String, String] = agentsProvider.getAvailableAgents.map(agent => agent.id -> agent.systemPrompt).toMap
  private val firebaseInitializer: FirebaseInitializer = new FirebaseInitializer(
    FirebaseConfig.getServiceAccountPath,
    FirebaseConfig.getStorageBucket
  )
  private val conversationRepository: ConversationRepository = new FirestoreConversationRepository(
    firebaseInitializer
  )
  private val tokenCountEstimator: TokenCountEstimator = new OpenAiTokenCountEstimator("gpt-4")
  private val documentContextService: DocumentContextService = createDocumentContextService()
  private val llmExchangeLogger = new LlmExchangeLogger(Path.of("logs", "llm-exchanges.log"))

  // --- Image model policy defaults ---
  private val defaultGeminiImageModel: String = ModelFactory.defaultGeminiImageModel
  private val defaultOpenAiImageModel: String = ModelFactory.defaultOpenAiImageModel
  private val defaultGrokImageModel: String = ModelFactory.defaultGrokImageModel
  private val defaultQwenCreateImageModel: String = ModelFactory.defaultQwenCreateImageModel
  private val defaultQwenEditImageModel: String = ModelFactory.defaultQwenEditImageModel

  override def premiumImageModel: ExposedImageModel = ExposedImageModel("gemini", defaultGeminiImageModel)

  override def freeImageModel: ExposedImageModel = ExposedImageModel("qwen", defaultQwenCreateImageModel)

  private val firebaseStorageUploader: FirebaseStorageUploader = createFirebaseStorageUploader()
  private val imageToolDependencies: ImageToolDependencies = loadImageToolDependencies()
  private val imageIntentClassifierPrompt: String =
    "Classify the user's intent for image operation from the message text only.\n" +
      "Return exactly one label: GENERATE_NEW, EDIT_EXISTING, or NONE.\n" +
      "Rules:\n" +
      "- GENERATE_NEW: user wants a new image to be created.\n" +
      "- EDIT_EXISTING: user wants to modify a previously shared image.\n" +
      "- NONE: user is not asking for image creation/editing.\n" +
      "Do not add any extra words."

  private enum PromptImageIntent {
    case GENERATE_NEW, EDIT_EXISTING, NONE, UNKNOWN
  }

  /**
   * Wraps a ChatStreamHandler to buffer onToken calls.
   * Used for image-intent requests so that a model refusal can be discarded
   * if the fallback image tool succeeds.
   */
  private class BufferingChatStreamHandler(delegate: ChatStreamHandler) extends ChatStreamHandler {
    private val buffer = new StringBuilder()
    @volatile private var passthrough = false

    /** Flush buffered tokens to the delegate and switch to passthrough mode. */
    def flushAndPassthrough(): Unit = synchronized {
      if (!passthrough) {
        passthrough = true
        if (buffer.nonEmpty) delegate.onToken(buffer.toString())
        buffer.clear()
      }
    }

    /** Discard buffered tokens and switch to passthrough mode. */
    def discardBuffer(): Unit = synchronized {
      buffer.clear()
      passthrough = true
    }

    override def onToken(token: String): Unit = synchronized {
      if (passthrough) delegate.onToken(token)
      else buffer.append(token)
    }

    override def onImageGenerationStarted(): Unit = delegate.onImageGenerationStarted()
  }

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

  override def createConversation(userContext: UserContext, conversationId: String): Boolean = {
    conversationRepository.createConversation(userContext, conversationId)
  }

  override def getConversationHistory(userContext: UserContext, conversationId: String): List[ConversationMessage] = {
    conversationRepository.getFullHistory(userContext, conversationId)
  }

  override def listConversations(userContext: UserContext): List[Conversation] = {
    conversationRepository.listConversations(userContext)
  }

  override def setConversationTitle(userContext: UserContext, conversationId: String, title: String): Unit = {
    conversationRepository.setConversationTitle(userContext, conversationId, title)
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
    modelId: String,
    agentId: Option[String],
    userContext: UserContext,
    streamHandler: ChatStreamHandler
  ): ChatResult = {
    val requestStartedAt = System.nanoTime()
    def traceStep[T](step: String)(block: => T): T = timed("chat/" + step, conversationId, userContext)(block)

    traceStep("createConversation") {
      conversationRepository.createConversation(userContext, conversationId)
    }
    val isPremium = userContext.isPremium.toBoolean

    val currentConfig = resolveModelConfig(modelId).getOrElse {
      throw new IllegalArgumentException("unknown modelId: " + modelId)
    }
    val providerName = currentConfig.getDisplayName.split(" ")(0)
    val modelName = currentConfig.model
    val promptImageIntent = traceStep("classifyPromptImageIntent") {
      classifyPromptImageIntent(currentConfig, message)
    }

    val systemPrompt = traceStep("buildSystemPrompt") {
      getSystemPromptForRequest(agentId)
    }
    lazy val requestHistory = traceStep("loadHistoryForEnrichment") {
      conversationRepository.getFullHistory(userContext, conversationId)
    }
    val messageForModel = traceStep("enrichMessageForModel") {
      enrichMessageForModel(userContext, conversationId, message, attachments, promptImageIntent, requestHistory)
    }
    val effectivePayload = traceStep("buildEffectivePayload") {
      buildEffectivePayload(userContext, conversationId, systemPrompt, messageForModel)
    }

    if (shouldUseDirectVisionAnswer(currentConfig, message, attachments)) {
      tryDirectVisionAnswer(userContext, conversationId, message, attachments, currentConfig, streamHandler, providerName, modelName) match {
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

    val isImageIntent = promptImageIntent == PromptImageIntent.GENERATE_NEW ||
                        promptImageIntent == PromptImageIntent.EDIT_EXISTING
    val bufferingHandler: Option[BufferingChatStreamHandler] =
      if (isImageIntent) Some(new BufferingChatStreamHandler(streamHandler)) else None
    val effectiveStreamHandler: ChatStreamHandler = bufferingHandler.getOrElse(streamHandler)

    val imageToolSession = createImageToolSession(
      isPremium = isPremium,
      config = currentConfig,
      onImageGenerationStarted = () => {
        bufferingHandler.foreach(_.flushAndPassthrough())
        streamHandler.onImageGenerationStarted()
      }
    )
    val assistant = createAssistant(currentConfig, imageToolSession, userContext)

    val done = new CountDownLatch(1)
    val responseBuffer = new StringBuilder()
    val errorHolder = new Array[String](1)

    try {
      val stream = traceStep("assistant.chat") {
        assistant.chat(conversationId, systemPrompt, messageForModel)
      }
      stream.onPartialResponse(token => {
        responseBuffer.append(token)
        effectiveStreamHandler.onToken(token)
      }).onCompleteResponse(_ => {
        done.countDown()
      }).onError(error => {
        errorHolder(0) = error.getMessage
        done.countDown()
      }).start()
      traceStep("streamAwait") {
        done.await()
      }

      // Persist user attachments
      if (attachments.nonEmpty) {
        traceStep("attachToLatestUserMessage") {
          conversationRepository.attachToLatestUserMessage(userContext, conversationId, attachments)
        }
      }
      val toolAttachments = traceStep("consumeToolAttachments") {
        consumeToolAttachments(userContext, conversationId, imageToolSession)
      }
      var latestAttachments =
        if (toolAttachments.nonEmpty) toolAttachments
        else traceStep("getLatestAiAttachments") {
          getLatestAiAttachments(userContext, conversationId)
        }
      val initialResponseText = responseBuffer.toString()
      if (shouldAttemptDirectImageToolFallback(message, initialResponseText, latestAttachments, errorHolder(0), imageToolSession, promptImageIntent)) {
        bufferingHandler.foreach(_.discardBuffer())
        val fallbackText = runDirectImageToolFallback(userContext, conversationId, message, messageForModel, attachments, imageToolSession, promptImageIntent)
        if (fallbackText != null && !fallbackText.isBlank) {
          streamHandler.onToken(fallbackText)
          responseBuffer.clear()
          responseBuffer.append(fallbackText)
          val fallbackAttachments = consumeToolAttachments(userContext, conversationId, imageToolSession)
          if (fallbackAttachments.nonEmpty) {
            latestAttachments = fallbackAttachments
          }
        }
      }
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
          getLatestAiAttachments(userContext, conversationId), error
        )
        ChatResult(Nil, error)
      case e: Exception =>
        val error = e.getMessage
        llmExchangeLogger.logExchange(
          providerName, modelName, message, messageForModel,
          effectivePayload, responseBuffer.toString(),
          getLatestAiAttachments(userContext, conversationId), error
        )
        ChatResult(Nil, error)
    }
    finally {
      val elapsedMs = (System.nanoTime() - requestStartedAt) / 1000000L
      println(s"[perf] chat/total conversationId=$conversationId user=${userContext.effectiveId} took ${elapsedMs}ms")
    }
  }

  override def clearConversation(userContext: UserContext, conversationId: String): Unit = {
    conversationRepository.clear(userContext, conversationId)
  }

  def getLogPath: String = llmExchangeLogger.getLogPath.toAbsolutePath.toString

  // ----------------------------------------------------------------
  // Internal wiring (per-request assistant creation)
  // ----------------------------------------------------------------

  private def createAssistant(config: ProviderConfig, imageToolSession: ImageToolSession, userContext: UserContext): Assistant = {
    val model: ChatModel = ModelFactory.createModel(config)
    val streamingModel: StreamingChatModel = ModelFactory.createStreamingModel(config)
    val memoryStore = memoryStoreFor(userContext)

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
    imageToolSession.tool.foreach(tool => builder.tools(tool))
    builder.build()
  }

  private def createImageToolSession(
    isPremium: Boolean,
    config: ProviderConfig,
    onImageGenerationStarted: () => Unit
  ): ImageToolSession = {
    if ("claude".equalsIgnoreCase(config.providerType)) {
      return ImageToolSession(None, None, new ImageGenerationTool.AttachmentCollector())
    }
    val selectedModel = if (isPremium) premiumImageModel else freeImageModel
    val provider = providerFromString(selectedModel.provider)
    provider match {
      case Some(p) =>
        val attachmentCollector = new ImageGenerationTool.AttachmentCollector()
        val tool: ImageGenerationTool = p match {
          case ImageGenerationTool.Provider.OPENAI =>
            new OpenAiImageGenerationTool(
              openAiImageModel = imageToolDependencies.openAiImageModel,
              openAiModelName = defaultOpenAiImageModel,
              firebaseStorageUploader = firebaseStorageUploader,
              attachmentCollector = attachmentCollector,
              onGenerationStarted = onImageGenerationStarted
            )
          case ImageGenerationTool.Provider.GEMINI =>
            new GeminiImageGenerationTool(
              geminiImageModel = imageToolDependencies.geminiImageModel,
              geminiModelName = defaultGeminiImageModel,
              firebaseStorageUploader = firebaseStorageUploader,
              attachmentCollector = attachmentCollector,
              onGenerationStarted = onImageGenerationStarted
            )
          case ImageGenerationTool.Provider.GROK =>
            new GrokImageGenerationTool(
              grokImageModel = imageToolDependencies.grokImageModel,
              grokModelName = defaultGrokImageModel,
              firebaseStorageUploader = firebaseStorageUploader,
              attachmentCollector = attachmentCollector,
              onGenerationStarted = onImageGenerationStarted
            )
          case ImageGenerationTool.Provider.QWEN =>
            new QwenImageGenerationTool(
              qwenApiKey = imageToolDependencies.qwenApiKey,
              qwenCreateModelName = defaultQwenCreateImageModel,
              qwenEditModelName = defaultQwenEditImageModel,
              qwenApiBaseUrl = ModelFactory.qwenApiBaseUrl,
              firebaseStorageUploader = firebaseStorageUploader,
              attachmentCollector = attachmentCollector,
              onGenerationStarted = onImageGenerationStarted
            )
        }
        ImageToolSession(Some(new ImageGenerationTool.ExposedTool(tool)), Some(tool), attachmentCollector)
      case None =>
        ImageToolSession(None, None, new ImageGenerationTool.AttachmentCollector())
    }
  }

  private def providerFromString(provider: String): Option[ImageGenerationTool.Provider] = {
    if (provider == null) {
      None
    } else if (provider.equalsIgnoreCase("gemini")) {
      Some(ImageGenerationTool.Provider.GEMINI)
    } else if (provider.equalsIgnoreCase("qwen")) {
      Some(ImageGenerationTool.Provider.QWEN)
    } else if (provider.equalsIgnoreCase("grok")) {
      Some(ImageGenerationTool.Provider.GROK)
    } else if (provider.equalsIgnoreCase("openai") || provider.equalsIgnoreCase("chatgpt")) {
      Some(ImageGenerationTool.Provider.OPENAI)
    } else {
      None
    }
  }

  private def loadImageToolDependencies(): ImageToolDependencies = {
    val openAiKey = ModelsConfig.getOpenAiKey
    val openAiModel: ImageModel = if (openAiKey == null || openAiKey.isBlank) {
      new DisabledImageModel()
    } else {
      ModelFactory.createImageModel(openAiKey, defaultOpenAiImageModel)
    }

    val geminiKey = ModelsConfig.getGeminiKey
    val geminiModel: ChatModel = if (geminiKey == null || geminiKey.isBlank) {
      null
    } else {
      ModelFactory.createGeminiImageModel(geminiKey, defaultGeminiImageModel)
    }

    val grokKey = ModelsConfig.getGrokKey
    val grokModel: ImageModel = if (grokKey == null || grokKey.isBlank) {
      new DisabledImageModel()
    } else {
      ModelFactory.createGrokImageModel(grokKey, defaultGrokImageModel)
    }

    val aliBabaKey = ModelsConfig.getAliBabaKey

    ImageToolDependencies(
      openAiImageModel = openAiModel,
      geminiImageModel = geminiModel,
      grokImageModel = grokModel,
      qwenApiKey = aliBabaKey
    )
  }

  private def createFirebaseStorageUploader(): FirebaseStorageUploader = {
    val uploader = new FirebaseStorageUploader(firebaseInitializer)
    uploader
  }

  private def createDocumentContextService(): DocumentContextService = {
    val gcsCache = new GcsDocumentArtifactCache(firebaseInitializer)
    val cache: DocumentArtifactCache = if (gcsCache.isConfigured) gcsCache else new NoopDocumentArtifactCache()
    new DocumentContextService(cache)
  }

  // ----------------------------------------------------------------
  // Message enrichment and attachment handling
  // ----------------------------------------------------------------

  private def enrichMessageForModel(
    userContext: UserContext,
    conversationId: String,
    message: String,
    attachments: List[MessageAttachment],
    promptImageIntent: PromptImageIntent,
    conversationHistory: List[ConversationMessage]
  ): String = {
    val enriched = enrichMessageWithLatestImageAttachment(userContext, conversationId, message, attachments, promptImageIntent, conversationHistory)
    enrichMessageWithLatestDocumentContext(userContext, conversationId, enriched, message, attachments, conversationHistory)
  }

  private def enrichMessageWithLatestImageAttachment(
    userContext: UserContext,
    conversationId: String,
    message: String,
    attachments: List[MessageAttachment],
    promptImageIntent: PromptImageIntent,
    conversationHistory: List[ConversationMessage]
  ): String = {
    if (extractFirstUrl(message) != null) {
      return message
    }
    val latestImageUrl = findLatestImageAttachmentUrl(userContext, conversationId, attachments, conversationHistory)
    if (latestImageUrl == null) {
      return message
    }
    val shouldInjectImageUrl =
      containsAttachmentType(attachments, "image") ||
        (promptImageIntent match {
          case PromptImageIntent.EDIT_EXISTING => true
          case PromptImageIntent.GENERATE_NEW  => false
          case PromptImageIntent.NONE          => false
          case PromptImageIntent.UNKNOWN =>
            referencesImage(message) || isImageEditRequest(message) || isImageFollowUpRequest(message)
        })
    if (!shouldInjectImageUrl) {
      return message
    }
    message + "\n\nSource image URL: " + latestImageUrl
  }

  private def enrichMessageWithLatestDocumentContext(
    userContext: UserContext,
    conversationId: String,
    messageForModel: String,
    originalMessage: String,
    attachments: List[MessageAttachment],
    conversationHistory: List[ConversationMessage]
  ): String = {
    val documentUrl = findLatestDocumentAttachmentUrl(userContext, conversationId, originalMessage, attachments, conversationHistory)
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

  private def findLatestImageAttachmentUrl(
    userContext: UserContext,
    conversationId: String,
    attachments: List[MessageAttachment],
    conversationHistory: List[ConversationMessage]
  ): String = {
    val pendingImage = findLatestAttachmentByType(attachments, "image")
    if (pendingImage != null && pendingImage.url != null && !pendingImage.url.isBlank) {
      return pendingImage.url
    }
    val history = conversationHistory
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

  private def findLatestDocumentAttachmentUrl(
    userContext: UserContext,
    conversationId: String,
    currentMessage: String,
    attachments: List[MessageAttachment],
    conversationHistory: List[ConversationMessage]
  ): String = {
    val pendingDoc = findLatestAttachmentByType(attachments, "document")
    if (pendingDoc != null && pendingDoc.url != null && !pendingDoc.url.isBlank) {
      return pendingDoc.url
    }
    val inCurrentMessage = MessageAttachmentExtractor.extractFromText(currentMessage)
    val currentDoc = findLatestAttachmentByType(inCurrentMessage, "document")
    if (currentDoc != null && currentDoc.url != null && !currentDoc.url.isBlank) {
      return currentDoc.url
    }
    val history = conversationHistory
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

  private def consumeToolAttachments(
    userContext: UserContext,
    conversationId: String,
    imageToolSession: ImageToolSession
  ): List[MessageAttachment] = {
    if (!imageToolSession.includeImageTools) {
      return Nil
    }
    val toolAttachments = imageToolSession.attachmentCollector.drain()
    if (toolAttachments.isEmpty) {
      return Nil
    }
    conversationRepository.attachToLatestAiMessage(userContext, conversationId, toolAttachments)
    val persistedOnAi = getLatestAiAttachments(userContext, conversationId).exists { existing =>
      existing != null && toolAttachments.exists(a => a != null && a.url == existing.url)
    }
    if (!persistedOnAi) {
      conversationRepository.attachToLatestUserMessage(userContext, conversationId, toolAttachments)
    }
    toolAttachments
  }

  private def getLatestAiAttachments(userContext: UserContext, conversationId: String): List[MessageAttachment] = {
    val history = conversationRepository.getFullHistory(userContext, conversationId)
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

  private def buildEffectivePayload(
    userContext: UserContext,
    conversationId: String,
    systemPrompt: String,
    currentUserMessage: String
  ): List[String] = {
    val memoryStore = memoryStoreFor(userContext)
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

  private def getSystemPromptForRequest(agentId: Option[String]): String = {
    agentId match {
      case Some(id) =>
        agentsById.get(id).getOrElse {
          throw new IllegalArgumentException("unknown agentId: " + id)
        }
      case None => defaultSystemPrompt
    }
  }

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
    userContext: UserContext,
    conversationId: String,
    message: String,
    attachments: List[MessageAttachment],
    modelConfig: ProviderConfig,
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
      val response: ChatResponse = ModelFactory.createModel(modelConfig).chat(userMessage)
      val aiText = Option(response.aiMessage()).map(_.text()).getOrElse("")

      conversationRepository.addMessage(userContext, conversationId, UserMessage.from(message))
      if (attachments.nonEmpty) {
        conversationRepository.attachToLatestUserMessage(userContext, conversationId, attachments)
      }
      conversationRepository.addMessage(userContext, conversationId, AiMessage.from(aiText))

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

  private def shouldAttemptDirectImageToolFallback(
    message: String,
    responseText: String,
    attachments: List[MessageAttachment],
    error: String,
    imageToolSession: ImageToolSession,
    promptImageIntent: PromptImageIntent
  ): Boolean = {
    if (!imageToolSession.includeImageTools || imageToolSession.rawTool.isEmpty) {
      return false
    }
    if (imageToolSession.rawTool.exists(_.hasExecutedInSession)) {
      return false
    }
    if (error != null && !error.isBlank) {
      return false
    }
    if (attachments != null && attachments.nonEmpty) {
      return false
    }
    val imageIntent = promptImageIntent match {
      case PromptImageIntent.GENERATE_NEW | PromptImageIntent.EDIT_EXISTING => true
      case PromptImageIntent.NONE                                            => isImageGenerationRequest(message) || isImageEditRequest(message)
      case PromptImageIntent.UNKNOWN                                         => isImageGenerationRequest(message) || isImageEditRequest(message)
    }
    if (!imageIntent) {
      return false
    }
    // For image intent, no image attachment means generation/edit did not complete.
    // Always force a direct tool call so users get deterministic provider output/error.
    true
  }

  private def runDirectImageToolFallback(
    userContext: UserContext,
    conversationId: String,
    message: String,
    messageForModel: String,
    attachments: List[MessageAttachment],
    imageToolSession: ImageToolSession,
    promptImageIntent: PromptImageIntent
  ): String = {
    val tool = imageToolSession.rawTool.orNull
    if (tool == null) {
      return null
    }
    val latestImageUrl = findLatestImageAttachmentUrl(
      userContext,
      conversationId,
      attachments,
      conversationRepository.getFullHistory(userContext, conversationId)
    )
    val shouldUseEdit = promptImageIntent == PromptImageIntent.EDIT_EXISTING && latestImageUrl != null && !latestImageUrl.isBlank
    if (shouldUseEdit) {
      tool.editImage(latestImageUrl, message)
    } else {
      tool.generateImage(messageForModel)
    }
  }

  private def classifyPromptImageIntent(config: ProviderConfig, message: String): PromptImageIntent = {
    if (message == null || message.isBlank) {
      return PromptImageIntent.NONE
    }
    try {
      val classifierPrompt =
        imageIntentClassifierPrompt + "\n\nUser message:\n" + message + "\n\nLabel:"
      val response: ChatResponse = ModelFactory.createModel(config).chat(UserMessage.from(classifierPrompt))
      val raw = Option(response.aiMessage()).map(_.text()).getOrElse("")
      normalizeIntentLabel(raw)
    } catch {
      case _: Exception => PromptImageIntent.UNKNOWN
    }
  }

  private def memoryStoreFor(userContext: UserContext): HistoryAwareChatMemoryStore = {
    new HistoryAwareChatMemoryStore(conversationRepository, userContext)
  }

  private def normalizeIntentLabel(raw: String): PromptImageIntent = {
    if (raw == null) {
      return PromptImageIntent.UNKNOWN
    }
    val upper = raw.trim.toUpperCase
    if (upper.startsWith("GENERATE_NEW")) {
      PromptImageIntent.GENERATE_NEW
    } else if (upper.startsWith("EDIT_EXISTING")) {
      PromptImageIntent.EDIT_EXISTING
    } else if (upper.startsWith("NONE")) {
      PromptImageIntent.NONE
    } else {
      PromptImageIntent.UNKNOWN
    }
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

  private case class ImageToolDependencies(
    openAiImageModel: ImageModel,
    geminiImageModel: ChatModel,
    grokImageModel: ImageModel,
    qwenApiKey: String
  )

  private case class ImageToolSession(
    tool: Option[AnyRef],
    rawTool: Option[ImageGenerationTool],
    attachmentCollector: ImageGenerationTool.AttachmentCollector
  ) {
    def includeImageTools: Boolean = tool.isDefined
  }

  private def timed[T](label: String, conversationId: String, userContext: UserContext)(block: => T): T = {
    val startedAt = System.nanoTime()
    try {
      block
    } finally {
      val elapsedMs = (System.nanoTime() - startedAt) / 1000000L
      println(s"[perf] $label conversationId=$conversationId user=${userContext.effectiveId} took ${elapsedMs}ms")
    }
  }

  private val defaultSystemPrompt: String =
    "You are a helpful assistant. Respond clearly and concisely. " +
      "If the user asks to create, draw, generate, edit, or modify images, you MUST call the generate_image tool. " +
      "IMPORTANT: Do NOT attempt to generate or create images yourself. You MUST use the generate_image tool for ALL image requests. " +
      "When the user wants to modify a previously generated image, call generate_image with a complete prompt that describes the full desired result including the requested changes. " +
      "For normal knowledge and reasoning questions, answer directly without image tools. " +
      "Return tool outputs directly when they satisfy the request."

  private def resolveModelConfig(modelId: String): Option[ProviderConfig] = {
    if (modelId == null || modelId.isBlank) None
    else configs.find(_.modelId == modelId)
  }
}
