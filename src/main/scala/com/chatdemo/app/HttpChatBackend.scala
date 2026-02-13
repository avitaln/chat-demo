package com.chatdemo.app

import com.chatdemo.common.config.ProviderConfig
import com.chatdemo.common.model.{Conversation, ConversationMessage, MessageAttachment, UserContext}
import com.chatdemo.common.service.{ChatBackend, ChatResult, ChatStreamHandler, ExposedImageModel, ImageModelAccessPolicy}
import com.fasterxml.jackson.databind.ObjectMapper

import java.io.{BufferedReader, InputStreamReader}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.Objects
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/**
 * ChatBackend implementation that delegates to the HTTP server.
 * Stateless: model selection and attachments are sent per-request.
 */
class HttpChatBackend(baseUrl: String) extends ChatBackend with ImageModelAccessPolicy {

  private val normalizedUrl: String =
    if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
  private val httpClient: HttpClient = HttpClient.newHttpClient()
  private val mapper: ObjectMapper = new ObjectMapper()
  private val defaultGeminiImageModel = "gemini-2.5-flash-image"
  private val defaultQwenImageModel = "qwen-image-max"

  override def premiumImageModel: ExposedImageModel = ExposedImageModel("gemini", defaultGeminiImageModel)

  override def freeImageModel: ExposedImageModel = ExposedImageModel("qwen", defaultQwenImageModel)

  // ----------------------------------------------------------------
  // Conversation management
  // ----------------------------------------------------------------

  override def createConversation(userContext: UserContext, conversationId: String): Boolean = {
    try {
      val requestBody = new java.util.LinkedHashMap[String, AnyRef]()
      requestBody.put("isPremium", userContext.isPremium)
      requestBody.put("deviceId", userContext.deviceId)
      userContext.signedInId.foreach(id => requestBody.put("signedInId", id))
      val json = mapper.writeValueAsString(requestBody)
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"$normalizedUrl/conversations/${encode(conversationId)}"))
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .header("Content-Type", "application/json")
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

  override def getConversationHistory(userContext: UserContext, conversationId: String): List[ConversationMessage] = {
    try {
      val uri = s"$normalizedUrl/conversations/${encode(conversationId)}?isPremium=${encode(userContext.isPremium)}&deviceId=${encode(userContext.deviceId)}${userContext.signedInId.map(id => s"&signedInId=${encode(id)}").getOrElse("")}"
      val request = HttpRequest.newBuilder()
        .uri(URI.create(uri))
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
        val createdAt = valueAsLong(m.get("createdAt"))

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

  override def listConversations(userContext: UserContext): List[Conversation] = {
    try {
      val uri = s"$normalizedUrl/conversations?isPremium=${encode(userContext.isPremium)}&deviceId=${encode(userContext.deviceId)}${userContext.signedInId.map(id => s"&signedInId=${encode(id)}").getOrElse("")}"
      val request = HttpRequest.newBuilder()
        .uri(URI.create(uri))
        .GET()
        .build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      val body = mapper.readValue(response.body(), classOf[java.util.Map[_, _]])
      val conversations = body.get("conversations").asInstanceOf[java.util.List[_]]
      if (conversations == null) return Nil
      conversations.asScala.map { value =>
        val conversation = value.asInstanceOf[java.util.Map[_, _]]
        Conversation(
          id = conversation.get("id").asInstanceOf[String],
          title = conversation.get("title").asInstanceOf[String],
          createdAt = valueAsLong(conversation.get("createdAt"))
        )
      }.toList
    } catch {
      case e: Exception =>
        throw operationFailure("list conversations", e)
    }
  }

  override def setConversationTitle(userContext: UserContext, conversationId: String, title: String): Unit = {
    try {
      val requestBody = new java.util.LinkedHashMap[String, AnyRef]()
      requestBody.put("title", title)
      requestBody.put("isPremium", userContext.isPremium)
      requestBody.put("deviceId", userContext.deviceId)
      userContext.signedInId.foreach(id => requestBody.put("signedInId", id))
      val json = mapper.writeValueAsString(requestBody)
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"$normalizedUrl/conversations/${encode(conversationId)}/title"))
        .PUT(HttpRequest.BodyPublishers.ofString(json))
        .header("Content-Type", "application/json")
        .build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() >= 400) {
        val backendError = safeBackendError(response.body())
        throw new RuntimeException(s"HTTP ${response.statusCode()}${if (backendError == null) "" else s": $backendError"}")
      }
    } catch {
      case e: Exception =>
        throw operationFailure("set conversation title", e)
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
          providerType = m.get("providerType").asInstanceOf[String],
          model = m.get("model").asInstanceOf[String],
          apiKey = "", // API key not exposed over HTTP
          id = m.get("id").asInstanceOf[String]
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
    modelId: String,
    agentId: Option[String],
    userContext: UserContext,
    streamHandler: ChatStreamHandler
  ): ChatResult = {
    try {
      val requestBody = new java.util.LinkedHashMap[String, AnyRef]()
      requestBody.put("message", message)
      requestBody.put("modelId", modelId)
      agentId.foreach(id => requestBody.put("agentId", id))
      requestBody.put("isPremium", userContext.isPremium)
      requestBody.put("deviceId", userContext.deviceId)
      userContext.signedInId.foreach(id => requestBody.put("signedInId", id))
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

  override def clearConversation(userContext: UserContext, conversationId: String): Unit = {
    try {
      val uri = s"$normalizedUrl/conversations/${encode(conversationId)}?isPremium=${encode(userContext.isPremium)}&deviceId=${encode(userContext.deviceId)}${userContext.signedInId.map(id => s"&signedInId=${encode(id)}").getOrElse("")}"
      val request = HttpRequest.newBuilder()
        .uri(URI.create(uri))
        .DELETE()
        .build()
      httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    } catch {
      case e: Exception =>
        throw operationFailure("clear conversation", e)
    }
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

  private def valueAsLong(value: Any): Long = {
    if (value == null) {
      0L
    } else {
      value match {
        case n: java.lang.Number => n.longValue()
        case s: String =>
          try {
            java.lang.Long.parseLong(s)
          } catch {
            case _: NumberFormatException => 0L
          }
        case _ => 0L
      }
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
