package com.chatdemo.app

import com.chatdemo.common.config.ProviderConfig
import com.chatdemo.common.model.{ConversationMessage, MessageAttachment}
import com.chatdemo.common.service.{ChatBackend, ChatResult, ChatStreamHandler, ImageModelStatus}
import com.fasterxml.jackson.databind.ObjectMapper

import java.io.{BufferedReader, InputStreamReader}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Objects
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/**
 * ChatBackend implementation that delegates to the HTTP server.
 * Stateless: model selection and attachments are sent per-request.
 */
class HttpChatBackend(baseUrl: String) extends ChatBackend {

  private val normalizedUrl: String =
    if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
  private val httpClient: HttpClient = HttpClient.newHttpClient()
  private val mapper: ObjectMapper = new ObjectMapper()

  // ----------------------------------------------------------------
  // Conversation management
  // ----------------------------------------------------------------

  override def createConversation(conversationId: String): Boolean = {
    try {
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"$normalizedUrl/conversations/${encode(conversationId)}"))
        .POST(HttpRequest.BodyPublishers.noBody())
        .build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      val statusCode = response.statusCode()
      val responseBody = response.body()
      if (statusCode == 201) {
        true
      } else if (statusCode == 200) {
        if (responseBody == null || responseBody.isBlank) {
          false
        } else {
          val body = mapper.readValue(responseBody, classOf[java.util.Map[_, _]])
          "created" == body.get("status")
        }
      } else if (statusCode >= 400) {
        val backendError = safeBackendError(responseBody)
        throw new RuntimeException(s"HTTP $statusCode${if (backendError == null) "" else s": $backendError"}")
      } else {
        // Accept successful but body-less variants from older servers.
        responseBody != null && !responseBody.isBlank
      }
    } catch {
      case e: Exception =>
        throw operationFailure("create conversation", e)
    }
  }

  override def getConversationHistory(conversationId: String): List[ConversationMessage] = {
    try {
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"$normalizedUrl/conversations/${encode(conversationId)}"))
        .GET()
        .build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      val body = mapper.readValue(response.body(), classOf[java.util.Map[_, _]])
      val messages = body.get("messages").asInstanceOf[java.util.List[_]]
      if (messages == null) return Nil

      val result = ArrayBuffer.empty[ConversationMessage]
      for (obj <- messages.asScala) {
        val m = obj.asInstanceOf[java.util.Map[_, _]]
        val msgType = m.get("type").asInstanceOf[String]
        val text = m.get("text").asInstanceOf[String]
        val id = m.get("id").asInstanceOf[String]
        val archived = java.lang.Boolean.TRUE == m.get("archived")
        val createdAt = Instant.parse(m.get("createdAt").asInstanceOf[String])

        val chatMsg: dev.langchain4j.data.message.ChatMessage = msgType match {
          case "ai"     => dev.langchain4j.data.message.AiMessage.from(text)
          case "system" => dev.langchain4j.data.message.SystemMessage.from(text)
          case _        => dev.langchain4j.data.message.UserMessage.from(text)
        }

        val attachments = deserializeAttachments(m.get("attachments").asInstanceOf[java.util.List[_]])
        result.append(new ConversationMessage(id, chatMsg, attachments, archived, createdAt))
      }
      result.toList
    } catch {
      case e: Exception =>
        throw operationFailure("get conversation history", e)
    }
  }

  override def listConversations(): List[String] = {
    try {
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"$normalizedUrl/conversations"))
        .GET()
        .build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      val body = mapper.readValue(response.body(), classOf[java.util.Map[_, _]])
      val conversations = body.get("conversations").asInstanceOf[java.util.List[_]]
      if (conversations == null) return Nil
      conversations.asScala.map(_.toString).toList
    } catch {
      case e: Exception =>
        throw operationFailure("list conversations", e)
    }
  }

  // ----------------------------------------------------------------
  // Model management (read-only)
  // ----------------------------------------------------------------

  override def getAvailableModels: List[ProviderConfig] = {
    try {
      val body = getModelsResponse()
      val models = body.get("models").asInstanceOf[java.util.List[_]]
      if (models == null) return Nil
      val result = ArrayBuffer.empty[ProviderConfig]
      for (obj <- models.asScala) {
        val m = obj.asInstanceOf[java.util.Map[_, _]]
        result.append(ProviderConfig(
          m.get("providerType").asInstanceOf[String],
          m.get("model").asInstanceOf[String],
          "" // API key not exposed over HTTP
        ))
      }
      result.toList
    } catch {
      case e: Exception =>
        throw operationFailure("get models", e)
    }
  }

  // ----------------------------------------------------------------
  // Chat (SSE streaming)
  // ----------------------------------------------------------------

  override def chat(
    conversationId: String,
    message: String,
    attachments: List[MessageAttachment],
    modelIndex: Int,
    isPremium: Boolean,
    streamHandler: ChatStreamHandler
  ): ChatResult = {
    try {
      val requestBody = new java.util.LinkedHashMap[String, AnyRef]()
      requestBody.put("message", message)
      requestBody.put("modelIndex", Int.box(modelIndex))
      requestBody.put("isPremium", java.lang.Boolean.valueOf(isPremium))
      if (attachments.nonEmpty) {
        requestBody.put("attachments", attachments.map(serializeAttachment).asJava)
      }
      val json = mapper.writeValueAsString(requestBody)
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"$normalizedUrl/conversations/${encode(conversationId)}/chat"))
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .header("Content-Type", "application/json")
        .build()

      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())

      val responseAttachments = ArrayBuffer.empty[MessageAttachment]
      var error: String = null

      val reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))
      try {
        var line = reader.readLine()
        while (line != null) {
          if (line.startsWith("data: ")) {
            val data = line.substring(6).trim
            if (data.nonEmpty) {
              val event = mapper.readValue(data, classOf[java.util.Map[_, _]])

              if (event.containsKey("token")) {
                streamHandler.onToken(event.get("token").asInstanceOf[String])
              }

              if (java.lang.Boolean.TRUE == event.get("done")) {
                if (event.containsKey("error")) {
                  error = event.get("error").asInstanceOf[String]
                }
                if (event.containsKey("attachments")) {
                  responseAttachments.appendAll(
                    deserializeAttachments(event.get("attachments").asInstanceOf[java.util.List[_]])
                  )
                }
              }
            }
          }
          line = reader.readLine()
        }
      } finally {
        reader.close()
      }

      ChatResult(responseAttachments.toList, error)
    } catch {
      case e: Exception =>
        ChatResult(Nil, "HTTP error: " + e.getMessage)
    }
  }

  override def clearConversation(conversationId: String): Unit = {
    try {
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"$normalizedUrl/conversations/${encode(conversationId)}"))
        .DELETE()
        .build()
      httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    } catch {
      case e: Exception =>
        throw operationFailure("clear conversation", e)
    }
  }

  // ----------------------------------------------------------------
  // Image model (delegated to server)
  // ----------------------------------------------------------------

  override def getImageModelStatus: ImageModelStatus = {
    ImageModelStatus("unknown", "unknown", "unknown", "unknown", "unknown", "unknown")
  }

  override def setImageModel(provider: String, modelName: String): ImageModelStatus = {
    getImageModelStatus
  }

  // ----------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------

  private def getModelsResponse(): java.util.Map[_, _] = {
    val request = HttpRequest.newBuilder()
      .uri(URI.create(s"$normalizedUrl/models"))
      .GET()
      .build()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    mapper.readValue(response.body(), classOf[java.util.Map[_, _]])
  }

  private def deserializeAttachments(list: java.util.List[_]): List[MessageAttachment] = {
    if (list == null) return Nil
    val result = ArrayBuffer.empty[MessageAttachment]
    for (obj <- list.asScala) {
      val a = obj.asInstanceOf[java.util.Map[_, _]]
      result.append(MessageAttachment(
        a.get("type").asInstanceOf[String],
        a.get("url").asInstanceOf[String],
        a.get("mimeType").asInstanceOf[String],
        a.get("title").asInstanceOf[String]
      ))
    }
    result.toList
  }

  private def serializeAttachment(attachment: MessageAttachment): java.util.Map[String, String] = {
    val map = new java.util.LinkedHashMap[String, String]()
    if (attachment.attachmentType != null) map.put("type", attachment.attachmentType)
    if (attachment.url != null) map.put("url", attachment.url)
    if (attachment.mimeType != null) map.put("mimeType", attachment.mimeType)
    if (attachment.title != null) map.put("title", attachment.title)
    map
  }

  private def encode(value: String): String = {
    java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
  }

  private def safeBackendError(body: String): String = {
    if (body == null || body.isBlank) {
      return null
    }
    try {
      val parsed = mapper.readValue(body, classOf[java.util.Map[_, _]])
      val error = parsed.get("error")
      if (error == null) null else error.toString
    } catch {
      case _: Exception => body.trim
    }
  }

  private def operationFailure(operation: String, error: Exception): RuntimeException = {
    val root = rootCause(error)
    val message =
      if (isConnectFailure(root))
        s"Failed to $operation: backend unavailable at $normalizedUrl. Start the server (for example with ./run.sh) or pass the correct server URL to Main."
      else
        s"Failed to $operation: ${safeErrorMessage(root)}"
    new RuntimeException(message, error)
  }

  private def rootCause(error: Throwable): Throwable = {
    var current = error
    while (current.getCause != null && !(current.getCause eq current)) {
      current = current.getCause
    }
    current
  }

  private def isConnectFailure(error: Throwable): Boolean = {
    error.isInstanceOf[java.net.ConnectException] ||
      error.isInstanceOf[java.nio.channels.ClosedChannelException]
  }

  private def safeErrorMessage(error: Throwable): String = {
    val direct = error.getMessage
    if (direct != null && direct.trim.nonEmpty) direct
    else Objects.toString(error.getClass.getSimpleName, "Unknown error")
  }
}
