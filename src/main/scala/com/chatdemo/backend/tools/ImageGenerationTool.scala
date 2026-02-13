package com.chatdemo.backend.tools

import com.chatdemo.backend.storage.FirebaseStorageUploader
import com.chatdemo.common.model.MessageAttachment
import dev.langchain4j.agent.tool.{P, Tool}
import dev.langchain4j.data.image.Image
import dev.langchain4j.data.message.{AiMessage, ImageContent, TextContent, UserMessage}
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.googleai.GeneratedImageHelper
import dev.langchain4j.model.image.{DisabledImageModel, ImageModel}
import dev.langchain4j.model.output.Response

import java.io.{ByteArrayOutputStream, IOException, InputStream}
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Instant
import java.util.{Base64, Queue}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/**
 * Tool for image generation and editing using OpenAI, Gemini, or Grok.
 */
class ImageGenerationTool {

  import ImageGenerationTool.Provider

  private var provider: Provider = Provider.OPENAI
  private var openAiImageModel: ImageModel = new DisabledImageModel()
  private var grokImageModel: ImageModel = new DisabledImageModel()
  private var geminiImageModel: ChatModel = _
  private var firebaseStorageUploader: FirebaseStorageUploader = _
  private var openAiModelName: String = "chatgpt-image-latest"
  private var geminiModelName: String = "gemini-2.5-flash-image"
  private var grokModelName: String = "grok-imagine-image"
  private var imageToolsEnabled: Boolean = true
  private var imageToolsDisabledReason: String = "Image generation is disabled for the current model."
  private val pendingAttachments: ConcurrentLinkedQueue[MessageAttachment] = new ConcurrentLinkedQueue[MessageAttachment]()

  def setOpenAiImageModel(model: ImageModel, modelName: String): Unit = {
    this.openAiImageModel = model
    this.openAiModelName = modelName
  }

  def setGeminiImageModel(model: ChatModel, modelName: String): Unit = {
    this.geminiImageModel = model
    this.geminiModelName = modelName
  }

  def setGrokImageModel(model: ImageModel, modelName: String): Unit = {
    this.grokImageModel = model
    this.grokModelName = modelName
  }

  def setFirebaseStorageUploader(uploader: FirebaseStorageUploader): Unit = {
    this.firebaseStorageUploader = uploader
  }

  def setProvider(p: Provider): Unit = {
    this.provider = p
  }

  def getProvider: Provider = provider

  def getCurrentModelName: String = {
    provider match {
      case Provider.GEMINI => geminiModelName
      case Provider.GROK   => grokModelName
      case Provider.OPENAI => openAiModelName
    }
  }

  def getOpenAiModelName: String = openAiModelName
  def getGeminiModelName: String = geminiModelName
  def getGrokModelName: String = grokModelName

  def disableImageTools(reason: String): Unit = {
    this.imageToolsEnabled = false
    this.imageToolsDisabledReason = if (reason == null || reason.isBlank) {
      "Image generation is disabled for the current model."
    } else {
      reason
    }
  }

  def enableImageTools(): Unit = {
    this.imageToolsEnabled = true
    this.imageToolsDisabledReason = "Image generation is disabled for the current model."
  }

  @Tool(name = "generate_image", value = Array(
    "Generate an image from a text description. Use only when the user explicitly asks to draw, create, or generate an image. " +
      "Do not use this tool for general knowledge, factual Q&A, or normal conversation."
  ))
  def generateImage(@P("Detailed description of the image to generate") prompt: String): String = {
    clearPendingAttachments()
    logToolCall("generate_image", s"provider=$provider, prompt=$prompt")
    try {
      if (!imageToolsEnabled) {
        val msg = "[Image Error] " + imageToolsDisabledReason
        logToolCall("generate_image", s"DISABLED: $msg")
        return msg
      }
      val result = provider match {
        case Provider.GEMINI => generateWithGemini(prompt)
        case Provider.GROK   => generateWithGrok(prompt)
        case Provider.OPENAI => generateWithOpenAi(prompt)
      }
      logToolCall("generate_image", s"result=$result")
      result
    } catch {
      case e: Exception =>
        val msg = "[Image Error] " + e.getMessage
        logToolCall("generate_image", s"EXCEPTION: ${e.getClass.getName}: ${e.getMessage}")
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
    clearPendingAttachments()
    logToolCall("edit_image", s"provider=$provider, model=${getCurrentModelName}, imageUrl=$imageUrl, prompt=$prompt")
    try {
      if (!imageToolsEnabled) {
        val msg = "[Image Error] " + imageToolsDisabledReason
        logToolCall("edit_image", s"DISABLED: $msg")
        return msg
      }
      if (provider == Provider.GEMINI) {
        if (geminiImageModel == null) {
          return "[Image Error] Gemini image generation is disabled. Set GEMINI_API_KEY to enable it."
        }
        val payload = downloadImage(imageUrl)
        val base64 = Base64.getEncoder.encodeToString(payload.bytes)
        val response: ChatResponse = geminiImageModel.chat(UserMessage.from(
          ImageContent.from(base64, payload.mimeType),
          TextContent.from(prompt)
        ))
        val aiMessage = response.aiMessage()
        if (!GeneratedImageHelper.hasGeneratedImages(aiMessage)) {
          return "[Image Error] Gemini did not return an image. Try /imagemodel gemini gemini-2.5-flash-image."
        }
        val images = GeneratedImageHelper.getGeneratedImages(aiMessage)
        if (images.isEmpty) {
          return "[Image Error] Gemini returned no images."
        }
        val result = formatImage(images.get(0))
        logToolCall("edit_image", s"result=$result")
        return result
      }
      if (provider == Provider.GROK) {
        if (grokImageModel.isInstanceOf[DisabledImageModel]) {
          return "[Image Error] Grok image editing is disabled. Set XAI_API_KEY to enable it."
        }
        val input = Image.builder().url(imageUrl).build()
        val response: Response[Image] = grokImageModel.edit(input, prompt)
        val result = formatImageResponse(response)
        logToolCall("edit_image", s"result=$result")
        return result
      }
      // OpenAI path — dall-e-3 does not support editing, only dall-e-2 and gpt-image-1 do.
      // Fall back to generate_image with a combined prompt when editing is unsupported.
      if (openAiImageModel.isInstanceOf[DisabledImageModel]) {
        return "[Image Error] Image editing is disabled. Set OPENAI_API_KEY to enable it."
      }
      if (openAiModelName == "dall-e-3") {
        logToolCall("edit_image", "dall-e-3 does not support edit, falling back to generate_image")
        val fallbackPrompt = s"Recreate the image with the following modification applied: $prompt"
        val result = generateWithOpenAi(fallbackPrompt)
        logToolCall("edit_image", s"fallback result=$result")
        return result
      }
      val input = Image.builder().url(imageUrl).build()
      val response: Response[Image] = openAiImageModel.edit(input, prompt)
      val result = formatImageResponse(response)
      logToolCall("edit_image", s"result=$result")
      result
    } catch {
      case e: Exception =>
        val msg = "[Image Error] " + e.getMessage
        logToolCall("edit_image", s"EXCEPTION: ${e.getClass.getName}: ${e.getMessage}")
        msg
    }
  }

  private def generateWithOpenAi(prompt: String): String = {
    if (openAiImageModel.isInstanceOf[DisabledImageModel]) {
      return "[Image Error] Image generation is disabled. Set OPENAI_API_KEY to enable it."
    }
    val response: Response[Image] = openAiImageModel.generate(prompt)
    formatImageResponse(response)
  }

  private def generateWithGemini(prompt: String): String = {
    if (geminiImageModel == null) {
      return "[Image Error] Gemini image generation is disabled. Set GEMINI_API_KEY to enable it."
    }
    val response: ChatResponse = geminiImageModel.chat(UserMessage.from(prompt))
    val aiMessage = response.aiMessage()
    if (!GeneratedImageHelper.hasGeneratedImages(aiMessage)) {
      return "[Image Error] Gemini did not return an image."
    }
    val images = GeneratedImageHelper.getGeneratedImages(aiMessage)
    if (images.isEmpty) {
      return "[Image Error] Gemini returned no images."
    }
    formatImage(images.get(0))
  }

  private def generateWithGrok(prompt: String): String = {
    if (grokImageModel.isInstanceOf[DisabledImageModel]) {
      return "[Image Error] Grok image generation is disabled. Set XAI_API_KEY to enable it."
    }
    val response: Response[Image] = grokImageModel.generate(prompt)
    formatImageResponse(response)
  }

  private def formatImageResponse(response: Response[Image]): String = {
    val image = response.content()
    if (image == null) {
      return "[Image Error] No image returned."
    }
    formatImage(image)
  }

  private def formatImage(image: Image): String = {
    if (image.url() != null) {
      val imageUrl = image.url().toString
      registerAttachment("image", imageUrl, inferMimeTypeFromImageUrl(imageUrl))
      return "Image generated successfully."
    }
    if (image.base64Data() != null) {
      val mimeType = if (image.mimeType() != null) image.mimeType() else "image/png"
      if (firebaseStorageUploader != null && firebaseStorageUploader.isConfigured) {
        val bytes = Base64.getDecoder.decode(image.base64Data())
        val providerName = provider.toString.toLowerCase
        val modelName = getCurrentModelName
        val url = firebaseStorageUploader.uploadBase64(bytes, mimeType, providerName, modelName)
        registerAttachment("image", url, mimeType)
        return "Image generated successfully."
      }
      val dataUrl = s"data:$mimeType;base64,${image.base64Data()}"
      registerAttachment("image", dataUrl, mimeType)
      return "Image generated successfully."
    }
    "[Image Error] Image response did not include a URL or base64 data."
  }

  def consumePendingAttachments(): List[MessageAttachment] = {
    if (pendingAttachments.isEmpty) {
      return Nil
    }
    val drained = ArrayBuffer.empty[MessageAttachment]
    var attachment = pendingAttachments.poll()
    while (attachment != null) {
      drained.append(attachment)
      attachment = pendingAttachments.poll()
    }
    drained.toList
  }

  private def clearPendingAttachments(): Unit = {
    pendingAttachments.clear()
  }

  private def registerAttachment(attachmentType: String, url: String, mimeType: String): Unit = {
    pendingAttachments.add(MessageAttachment(attachmentType, url, mimeType, null))
  }

  private def inferMimeTypeFromImageUrl(imageUrl: String): String = {
    if (imageUrl == null) {
      return "image/png"
    }
    val lower = imageUrl.toLowerCase
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) "image/jpeg"
    else if (lower.endsWith(".gif")) "image/gif"
    else if (lower.endsWith(".webp")) "image/webp"
    else if (lower.endsWith(".bmp")) "image/bmp"
    else if (lower.endsWith(".svg")) "image/svg+xml"
    else "image/png"
  }

  @throws[IOException]
  private def downloadImage(imageUrl: String): ImagePayload = {
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

  private def guessMimeType(imageUrl: String): String = {
    val lower = imageUrl.toLowerCase
    if (lower.endsWith(".png")) "image/png"
    else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) "image/jpeg"
    else if (lower.endsWith(".gif")) "image/gif"
    else if (lower.endsWith(".webp")) "image/webp"
    else if (lower.endsWith(".bmp")) "image/bmp"
    else null
  }

  private def logToolCall(toolName: String, message: String): Unit = {
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

  private case class ImagePayload(bytes: Array[Byte], mimeType: String)
}

object ImageGenerationTool {
  enum Provider {
    case OPENAI, GEMINI, GROK
  }
}
