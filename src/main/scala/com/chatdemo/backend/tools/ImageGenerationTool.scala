package com.chatdemo.backend.tools

import com.chatdemo.backend.storage.FirebaseStorageUploader
import com.chatdemo.common.model.MessageAttachment
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import dev.langchain4j.agent.tool.{P, Tool}
import dev.langchain4j.data.image.Image
import dev.langchain4j.data.message.{ImageContent, TextContent, UserMessage}
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.googleai.GeneratedImageHelper
import dev.langchain4j.model.image.{DisabledImageModel, ImageModel}
import dev.langchain4j.model.output.Response

import java.io.{ByteArrayOutputStream, IOException, InputStream, OutputStream}
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable.ArrayBuffer

/**
 * Base tool for image generation/editing with provider-specific subclasses.
 */
abstract class ImageGenerationTool(
  val provider: ImageGenerationTool.Provider,
  val firebaseStorageUploader: FirebaseStorageUploader,
  private val attachmentCollector: ImageGenerationTool.AttachmentCollector = new ImageGenerationTool.AttachmentCollector(),
  private val onGenerationStarted: () => Unit = () => {}
) {
  private var providerExecutedInSession: Boolean = false
  private var cachedSessionResult: String = null

  protected def currentModelName: String
  protected def generateWithProvider(prompt: String): String
  protected def editWithProvider(imageUrl: String, prompt: String): String

  final def getCurrentModelName: String = currentModelName
  final def hasExecutedInSession: Boolean = providerExecutedInSession

  @Tool(name = "generate_image", value = Array(
    "Generate an image from a text description. Use only when the user explicitly asks to draw, create, or generate an image. " +
      "Do not use this tool for general knowledge, factual Q&A, or normal conversation."
  ))
  final def generateImage(@P("Detailed description of the image to generate") prompt: String): String = {
    if (providerExecutedInSession) {
      logToolCall("generate_image", "skipping duplicate tool execution in same request")
      return cachedSessionResult
    }
    clearPendingAttachments()
    onGenerationStarted()
    logToolCall("generate_image", s"provider=$provider, prompt=$prompt")
    try {
      val result = generateWithProvider(prompt)
      providerExecutedInSession = true
      cachedSessionResult = result
      logToolCall("generate_image", s"result=$result")
      result
    } catch {
      case e: Exception =>
        val details = buildErrorDetails(e)
        val msg = "[Image Error] " + details
        providerExecutedInSession = true
        cachedSessionResult = msg
        logToolCall("generate_image", s"EXCEPTION: ${e.getClass.getName}: $details")
        msg
    }
  }

  @Tool(name = "edit_image", value = Array(
    "Edit or transform an existing image using a provided image URL. Use only when the user explicitly asks to modify an image. " +
      "Do not use this tool for general knowledge, factual Q&A, or normal conversation."
  ))
  def editImage(
    @P("URL of the source image to edit") imageUrl: String,
    @P("Detailed description of the desired edits") prompt: String
  ): String = {
    if (providerExecutedInSession) {
      logToolCall("edit_image", "skipping duplicate tool execution in same request")
      return cachedSessionResult
    }
    clearPendingAttachments()
    onGenerationStarted()
    logToolCall("edit_image", s"provider=$provider, model=$currentModelName, imageUrl=$imageUrl, prompt=$prompt")
    try {
      val result = editWithProvider(imageUrl, prompt)
      providerExecutedInSession = true
      cachedSessionResult = result
      logToolCall("edit_image", s"result=$result")
      result
    } catch {
      case e: Exception =>
        val details = buildErrorDetails(e)
        val msg = "[Image Error] " + details
        providerExecutedInSession = true
        cachedSessionResult = msg
        logToolCall("edit_image", s"EXCEPTION: ${e.getClass.getName}: $details")
        msg
    }
  }

  protected final def buildErrorDetails(e: Exception): String = {
    val httpDetails = extractHttpExceptionDetails(e)
    if (httpDetails != null && !httpDetails.isBlank) {
      return httpDetails
    }

    val message = e.getMessage
    if (message != null && !message.isBlank) message
    else {
      val root = rootCause(e)
      val rootMessage = if (root == null) null else root.getMessage
      if (rootMessage != null && !rootMessage.isBlank) {
        return root.getClass.getSimpleName + ": " + rootMessage
      }
      val asString = e.toString
      if (asString != null && !asString.isBlank) asString
      else e.getClass.getName
    }
  }

  private def extractHttpExceptionDetails(e: Exception): String = {
    if (e == null || !e.getClass.getName.endsWith("HttpException")) {
      return null
    }
    val status = firstNonBlank(
      invokeNoArg(e, "statusCode"),
      invokeNoArg(e, "code"),
      invokeNoArg(e, "status")
    )
    val body = firstNonBlank(
      invokeNoArg(e, "responseBody"),
      invokeNoArg(e, "body"),
      invokeNoArg(e, "errorBody")
    )
    val base = e.getClass.getSimpleName
    if (status != null && body != null) {
      s"$base status=$status body=$body"
    } else if (status != null) {
      s"$base status=$status"
    } else if (body != null) {
      s"$base body=$body"
    } else {
      null
    }
  }

  private def invokeNoArg(target: AnyRef, methodName: String): String = {
    try {
      val method = target.getClass.getMethod(methodName)
      val value = method.invoke(target)
      if (value == null) null else value.toString
    } catch {
      case _: Exception => null
    }
  }

  private def rootCause(error: Throwable): Throwable = {
    var current = error
    while (current != null && current.getCause != null && !(current.getCause eq current)) {
      current = current.getCause
    }
    current
  }

  private def firstNonBlank(values: String*): String = {
    values.find(v => v != null && !v.isBlank).orNull
  }

  protected final def formatImageResponse(response: Response[Image]): String = {
    val image = response.content()
    if (image == null) "[Image Error] No image returned."
    else formatImage(image)
  }

  protected final def formatImage(image: Image): String = {
    if (image.url() != null) {
      val imageUrl = image.url().toString
      registerAttachment("image", imageUrl, inferMimeTypeFromImageUrl(imageUrl))
      "Image generated successfully."
    } else if (image.base64Data() != null) {
      val mimeType = if (image.mimeType() != null) image.mimeType() else "image/png"
      if (firebaseStorageUploader != null && firebaseStorageUploader.isConfigured) {
        val bytes = Base64.getDecoder.decode(image.base64Data())
        val providerName = provider.toString.toLowerCase
        val modelName = currentModelName
        val url = firebaseStorageUploader.uploadBase64(bytes, mimeType, providerName, modelName)
        registerAttachment("image", url, mimeType)
        "Image generated successfully."
      } else {
        val dataUrl = s"data:$mimeType;base64,${image.base64Data()}"
        registerAttachment("image", dataUrl, mimeType)
        "Image generated successfully."
      }
    } else {
      "[Image Error] Image response did not include a URL or base64 data."
    }
  }

  final def consumePendingAttachments(): List[MessageAttachment] = {
    attachmentCollector.drain()
  }

  private final def clearPendingAttachments(): Unit = {
    attachmentCollector.clear()
  }

  protected final def registerAttachment(attachmentType: String, url: String, mimeType: String): Unit = {
    attachmentCollector.add(MessageAttachment(attachmentType, url, mimeType, null))
  }

  protected final def inferMimeTypeFromImageUrl(imageUrl: String): String = {
    if (imageUrl == null) "image/png"
    else {
      val lower = imageUrl.toLowerCase
      if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) "image/jpeg"
      else if (lower.endsWith(".gif")) "image/gif"
      else if (lower.endsWith(".webp")) "image/webp"
      else if (lower.endsWith(".bmp")) "image/bmp"
      else if (lower.endsWith(".svg")) "image/svg+xml"
      else "image/png"
    }
  }

  @throws[IOException]
  protected final def downloadImage(imageUrl: String): ImagePayload = {
    val url = new URL(imageUrl)
    val connection = url.openConnection().asInstanceOf[HttpURLConnection]
    connection.setInstanceFollowRedirects(true)
    connection.setRequestProperty("User-Agent", "chat-demo")
    val status = connection.getResponseCode
    val inputStream = if (status >= 200 && status < 300) {
      connection.getInputStream
    } else {
      connection.getErrorStream
    }
    if (inputStream == null) {
      throw new IOException("Unable to download image: HTTP " + status)
    }
    val bytes = readAllBytes(inputStream)
    var mimeType = connection.getContentType
    if (mimeType != null) {
      val separator = mimeType.indexOf(';')
      mimeType = if (separator > 0) mimeType.substring(0, separator).trim else mimeType.trim
    }
    if (mimeType == null || mimeType.isBlank) {
      mimeType = guessMimeType(imageUrl)
    }
    if (mimeType == null || mimeType.isBlank) {
      mimeType = "image/png"
    }
    ImagePayload(bytes, mimeType)
  }

  private final def readAllBytes(inputStream: InputStream): Array[Byte] = {
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

  private final def guessMimeType(imageUrl: String): String = {
    val lower = imageUrl.toLowerCase
    if (lower.endsWith(".png")) "image/png"
    else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) "image/jpeg"
    else if (lower.endsWith(".gif")) "image/gif"
    else if (lower.endsWith(".webp")) "image/webp"
    else if (lower.endsWith(".bmp")) "image/bmp"
    else null
  }

  protected final def logToolCall(toolName: String, message: String): Unit = {
    try {
      val logDir = Path.of("logs")
      if (!Files.exists(logDir)) Files.createDirectories(logDir)
      val logFile = logDir.resolve("image-tool.log")
      val line = s"[${Instant.now()}] [$toolName] $message\n"
      Files.writeString(logFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    } catch {
      case _: Exception => // best-effort logging
    }
  }

  protected case class ImagePayload(bytes: Array[Byte], mimeType: String)
}

final class OpenAiImageGenerationTool(
  openAiImageModel: ImageModel,
  openAiModelName: String,
  firebaseStorageUploader: FirebaseStorageUploader,
  attachmentCollector: ImageGenerationTool.AttachmentCollector = new ImageGenerationTool.AttachmentCollector(),
  onGenerationStarted: () => Unit = () => {}
) extends ImageGenerationTool(
  provider = ImageGenerationTool.Provider.OPENAI,
  firebaseStorageUploader = firebaseStorageUploader,
  attachmentCollector = attachmentCollector,
  onGenerationStarted = onGenerationStarted
) {
  override protected def currentModelName: String = openAiModelName

  override protected def generateWithProvider(prompt: String): String = {
    if (openAiImageModel.isInstanceOf[DisabledImageModel]) {
      "[Image Error] Image generation is disabled. Set OPENAI_API_KEY to enable it."
    } else {
      val response: Response[Image] = openAiImageModel.generate(prompt)
      formatImageResponse(response)
    }
  }

  override protected def editWithProvider(imageUrl: String, prompt: String): String = {
    if (openAiImageModel.isInstanceOf[DisabledImageModel]) {
      "[Image Error] Image editing is disabled. Set OPENAI_API_KEY to enable it."
    } else if (openAiModelName == "dall-e-3") {
      logToolCall("edit_image", "dall-e-3 does not support edit, falling back to generate_image")
      val fallbackPrompt = s"Recreate the image with the following modification applied: $prompt"
      generateWithProvider(fallbackPrompt)
    } else {
      val input = Image.builder().url(imageUrl).build()
      val response: Response[Image] = openAiImageModel.edit(input, prompt)
      formatImageResponse(response)
    }
  }
}

final class GeminiImageGenerationTool(
  geminiImageModel: ChatModel,
  geminiModelName: String,
  firebaseStorageUploader: FirebaseStorageUploader,
  attachmentCollector: ImageGenerationTool.AttachmentCollector = new ImageGenerationTool.AttachmentCollector(),
  onGenerationStarted: () => Unit = () => {}
) extends ImageGenerationTool(
  provider = ImageGenerationTool.Provider.GEMINI,
  firebaseStorageUploader = firebaseStorageUploader,
  attachmentCollector = attachmentCollector,
  onGenerationStarted = onGenerationStarted
) {
  override protected def currentModelName: String = geminiModelName

  override protected def generateWithProvider(prompt: String): String = {
    if (geminiImageModel == null) {
      "[Image Error] Gemini image generation is disabled. Set GEMINI_API_KEY to enable it."
    } else {
      val response: ChatResponse = geminiImageModel.chat(UserMessage.from(prompt))
      val aiMessage = response.aiMessage()
      if (!GeneratedImageHelper.hasGeneratedImages(aiMessage)) {
        "[Image Error] Gemini did not return an image."
      } else {
        val images = GeneratedImageHelper.getGeneratedImages(aiMessage)
        if (images.isEmpty) "[Image Error] Gemini returned no images."
        else formatImage(images.get(0))
      }
    }
  }

  override protected def editWithProvider(imageUrl: String, prompt: String): String = {
    if (geminiImageModel == null) {
      "[Image Error] Gemini image generation is disabled. Set GEMINI_API_KEY to enable it."
    } else {
      val payload = downloadImage(imageUrl)
      val base64 = Base64.getEncoder.encodeToString(payload.bytes)
      val response: ChatResponse = geminiImageModel.chat(UserMessage.from(
        ImageContent.from(base64, payload.mimeType),
        TextContent.from(prompt)
      ))
      val aiMessage = response.aiMessage()
      if (!GeneratedImageHelper.hasGeneratedImages(aiMessage)) {
        "[Image Error] Gemini did not return an image. Try /imagemodel gemini gemini-2.5-flash-image."
      } else {
        val images = GeneratedImageHelper.getGeneratedImages(aiMessage)
        if (images.isEmpty) "[Image Error] Gemini returned no images."
        else formatImage(images.get(0))
      }
    }
  }
}

final class GrokImageGenerationTool(
  grokImageModel: ImageModel,
  grokModelName: String,
  firebaseStorageUploader: FirebaseStorageUploader,
  attachmentCollector: ImageGenerationTool.AttachmentCollector = new ImageGenerationTool.AttachmentCollector(),
  onGenerationStarted: () => Unit = () => {}
) extends ImageGenerationTool(
  provider = ImageGenerationTool.Provider.GROK,
  firebaseStorageUploader = firebaseStorageUploader,
  attachmentCollector = attachmentCollector,
  onGenerationStarted = onGenerationStarted
) {
  override protected def currentModelName: String = grokModelName

  override protected def generateWithProvider(prompt: String): String = {
    if (grokImageModel.isInstanceOf[DisabledImageModel]) {
      "[Image Error] Grok image generation is disabled. Set XAI_API_KEY to enable it."
    } else {
      val response: Response[Image] = grokImageModel.generate(prompt)
      formatImageResponse(response)
    }
  }

  override protected def editWithProvider(imageUrl: String, prompt: String): String = {
    if (grokImageModel.isInstanceOf[DisabledImageModel]) {
      "[Image Error] Grok image editing is disabled. Set XAI_API_KEY to enable it."
    } else {
      val input = Image.builder().url(imageUrl).build()
      val response: Response[Image] = grokImageModel.edit(input, prompt)
      formatImageResponse(response)
    }
  }
}

final class QwenImageGenerationTool(
  qwenApiKey: String,
  qwenCreateModelName: String,
  qwenEditModelName: String,
  qwenApiBaseUrl: String,
  firebaseStorageUploader: FirebaseStorageUploader,
  attachmentCollector: ImageGenerationTool.AttachmentCollector = new ImageGenerationTool.AttachmentCollector(),
  onGenerationStarted: () => Unit = () => {}
) extends ImageGenerationTool(
  provider = ImageGenerationTool.Provider.QWEN,
  firebaseStorageUploader = firebaseStorageUploader,
  attachmentCollector = attachmentCollector,
  onGenerationStarted = onGenerationStarted
) {
  private val mapper = new ObjectMapper()

  override protected def currentModelName: String = qwenCreateModelName

  override protected def generateWithProvider(prompt: String): String = {
    if (qwenApiKey == null || qwenApiKey.isBlank) {
      "[Image Error] Qwen image generation is disabled. Set ALI_BABA_KEY to enable it."
    } else {
      callQwenImageApi(qwenCreateModelName, prompt, None)
    }
  }

  override protected def editWithProvider(imageUrl: String, prompt: String): String = {
    if (qwenApiKey == null || qwenApiKey.isBlank) {
      "[Image Error] Qwen image editing is disabled. Set ALI_BABA_KEY to enable it."
    } else {
      callQwenImageApi(qwenEditModelName, prompt, Some(imageUrl))
    }
  }

  private def callQwenImageApi(modelName: String, prompt: String, imageUrl: Option[String]): String = {
    val endpoint = qwenApiBaseUrl.stripSuffix("/") + "/services/aigc/multimodal-generation/generation"
    val payload = buildQwenPayload(modelName, prompt, imageUrl)
    val responseText = executeQwenRequest(endpoint, payload)
    val root = mapper.readTree(responseText)

    val errorCode = textAt(root, "/code")
    if (errorCode != null) {
      val errorMessage = textAt(root, "/message")
      val details = if (errorMessage == null || errorMessage.isBlank) errorCode else s"$errorCode: $errorMessage"
      return "[Image Error] " + details
    }

    val imageNode = firstImageNode(root)
    if (imageNode == null || imageNode.isBlank) {
      "[Image Error] Qwen response did not include an image URL."
    } else {
      if (firebaseStorageUploader == null || !firebaseStorageUploader.isConfigured) {
        "[Image Error] Firebase upload is required for Qwen images but is not configured."
      } else {
        try {
          val payload = downloadImage(imageNode)
          val firebaseUrl = firebaseStorageUploader.uploadBase64(payload.bytes, payload.mimeType, "qwen", modelName)
          registerAttachment("image", firebaseUrl, payload.mimeType)
          "Image generated successfully."
        } catch {
          case e: Exception =>
            "[Image Error] Failed to persist Qwen image to Firebase: " + buildErrorDetails(e)
        }
      }
    }
  }

  private def executeQwenRequest(endpoint: String, payload: String): String = {
    val connection = new URL(endpoint).openConnection().asInstanceOf[HttpURLConnection]
    connection.setRequestMethod("POST")
    connection.setDoOutput(true)
    connection.setConnectTimeout(20000)
    connection.setReadTimeout(120000)
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setRequestProperty("Authorization", "Bearer " + qwenApiKey)

    var outputStream: OutputStream = null
    try {
      outputStream = connection.getOutputStream
      outputStream.write(payload.getBytes(StandardCharsets.UTF_8))
      outputStream.flush()
    } finally {
      if (outputStream != null) {
        outputStream.close()
      }
    }

    val status = connection.getResponseCode
    val responseBody = readConnectionBody(connection)
    if (status >= 200 && status < 300) {
      responseBody
    } else {
      val errorBody = if (responseBody == null || responseBody.isBlank) "(empty)" else responseBody
      throw new IOException(s"Qwen HTTP $status: $errorBody")
    }
  }

  private def readConnectionBody(connection: HttpURLConnection): String = {
    val inputStream = if (connection.getResponseCode >= 200 && connection.getResponseCode < 300) {
      connection.getInputStream
    } else {
      connection.getErrorStream
    }
    if (inputStream == null) {
      return ""
    }
    val bytes = readStreamAllBytes(inputStream)
    new String(bytes, StandardCharsets.UTF_8)
  }

  private def readStreamAllBytes(inputStream: InputStream): Array[Byte] = {
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

  private def buildQwenPayload(modelName: String, prompt: String, imageUrl: Option[String]): String = {
    val root = mapper.createObjectNode()
    root.put("model", modelName)
    val input = root.putObject("input")
    val messages = input.putArray("messages")
    val message = messages.addObject()
    message.put("role", "user")
    val content = message.putArray("content")
    imageUrl.foreach(url => content.addObject().put("image", url))
    content.addObject().put("text", prompt)
    val parameters = root.putObject("parameters")
    parameters.put("watermark", false)
    parameters.put("prompt_extend", true)
    root.toString
  }

  private def firstImageNode(root: JsonNode): String = {
    val choices = root.path("output").path("choices")
    if (!choices.isArray || choices.isEmpty) {
      return null
    }
    val content = choices.get(0).path("message").path("content")
    if (!content.isArray || content.isEmpty) {
      return null
    }
    var i = 0
    while (i < content.size()) {
      val image = content.get(i).path("image")
      if (!image.isMissingNode && !image.isNull && image.asText() != null && !image.asText().isBlank) {
        return image.asText()
      }
      i += 1
    }
    null
  }

  private def textAt(root: JsonNode, pointer: String): String = {
    val node = root.at(pointer)
    if (node == null || node.isMissingNode || node.isNull) null else node.asText()
  }
}

object ImageGenerationTool {
  final class ExposedTool(delegate: ImageGenerationTool) {
    @Tool(name = "generate_image", value = Array(
      "Generate an image from a text description. Use only when the user explicitly asks to draw, create, or generate an image. " +
        "Do not use this tool for general knowledge, factual Q&A, or normal conversation."
    ))
    def generateImage(@P("Detailed description of the image to generate") prompt: String): String =
      delegate.generateImage(prompt)

    @Tool(name = "edit_image", value = Array(
      "Edit or transform an existing image using a provided image URL. Use only when the user explicitly asks to modify an image. " +
        "Do not use this tool for general knowledge, factual Q&A, or normal conversation."
    ))
    def editImage(
      @P("URL of the source image to edit") imageUrl: String,
      @P("Detailed description of the desired edits") prompt: String
    ): String = delegate.editImage(imageUrl, prompt)
  }

  final class AttachmentCollector {
    private val pendingAttachments: ConcurrentLinkedQueue[MessageAttachment] = new ConcurrentLinkedQueue[MessageAttachment]()

    def clear(): Unit = pendingAttachments.clear()

    def add(attachment: MessageAttachment): Unit = pendingAttachments.add(attachment)

    def drain(): List[MessageAttachment] = {
      if (pendingAttachments.isEmpty) {
        Nil
      } else {
        val drained = ArrayBuffer.empty[MessageAttachment]
        var attachment = pendingAttachments.poll()
        while (attachment != null) {
          drained.append(attachment)
          attachment = pendingAttachments.poll()
        }
        drained.toList
      }
    }
  }

  enum Provider {
    case OPENAI, GEMINI, GROK, QWEN
  }
}
