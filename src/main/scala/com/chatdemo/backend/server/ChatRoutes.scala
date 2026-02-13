package com.chatdemo.backend.server

import com.chatdemo.common.config.ProviderConfig
import com.chatdemo.common.model.{ConversationMessage, MessageAttachment}
import com.chatdemo.common.service.{ChatBackend, ChatResult, ChatStreamHandler, ImageModelStatus}
import com.fasterxml.jackson.databind.ObjectMapper
import io.undertow.server.{HttpHandler, HttpServerExchange, RoutingHandler}
import io.undertow.util.{Headers, PathTemplateMatch, StatusCodes}

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.{HashMap => JHashMap, LinkedHashMap => JLinkedHashMap, Map => JMap}
import scala.jdk.CollectionConverters.*

/**
 * Undertow HTTP handlers that expose the ChatBackend as a REST/SSE API.
 *
 * Stateless: model selection and attachments are provided per-request
 * in the chat payload rather than via separate state-changing endpoints.
 */
class ChatRoutes(private val backend: ChatBackend) {

  private val mapper = new ObjectMapper()

  def buildHandler(): HttpHandler = {
    new RoutingHandler()
      .post("/conversations/{id}", (exchange: HttpServerExchange) => createConversation(exchange))
      .get("/conversations/{id}", (exchange: HttpServerExchange) => getConversation(exchange))
      .get("/conversations", (exchange: HttpServerExchange) => listConversations(exchange))
      .delete("/conversations/{id}", (exchange: HttpServerExchange) => deleteConversation(exchange))
      .post("/conversations/{id}/chat", (exchange: HttpServerExchange) => chat(exchange))
      .get("/models", (exchange: HttpServerExchange) => getModels(exchange))
      .setFallbackHandler((exchange: HttpServerExchange) => notFound(exchange))
  }

  // ----------------------------------------------------------------
  // Conversation endpoints
  // ----------------------------------------------------------------

  private def createConversation(exchange: HttpServerExchange): Unit = {
    val id = conversationId(exchange)
    if (id == null || id.isBlank) {
      sendJson(exchange, StatusCodes.BAD_REQUEST, JMap.of("error", "conversation id is required"))
      return
    }
    val created = backend.createConversation(id)
    if (created) {
      sendJson(exchange, StatusCodes.CREATED, JMap.of("id", id, "status", "created"))
    } else {
      sendJson(exchange, StatusCodes.OK, JMap.of("id", id, "status", "already_exists"))
    }
  }

  private def getConversation(exchange: HttpServerExchange): Unit = {
    val id = conversationId(exchange)
    if (id == null || id.isBlank) {
      sendJson(exchange, StatusCodes.BAD_REQUEST, JMap.of("error", "conversation id is required"))
      return
    }
    val history = backend.getConversationHistory(id)
    val messages: java.util.List[JMap[String, AnyRef]] = history.map(serializeMessage).asJava
    sendJson(exchange, StatusCodes.OK, JMap.of("id", id, "messages", messages))
  }

  private def listConversations(exchange: HttpServerExchange): Unit = {
    val ids = backend.listConversations()
    sendJson(exchange, StatusCodes.OK, JMap.of("conversations", ids.asJava))
  }

  private def deleteConversation(exchange: HttpServerExchange): Unit = {
    val id = conversationId(exchange)
    if (id == null || id.isBlank) {
      sendJson(exchange, StatusCodes.BAD_REQUEST, JMap.of("error", "conversation id is required"))
      return
    }
    backend.clearConversation(id)
    sendJson(exchange, StatusCodes.OK, JMap.of("id", id, "status", "cleared"))
  }

  // ----------------------------------------------------------------
  // Chat endpoint (SSE streaming)
  // ----------------------------------------------------------------

  private def chat(exchange: HttpServerExchange): Unit = {
    exchange.getRequestReceiver.receiveFullBytes((ex: HttpServerExchange, bytes: Array[Byte]) => {
      ex.dispatch(new Runnable {
        override def run(): Unit = {
          try {
            val id = conversationId(ex)
            if (id == null || id.isBlank) {
              sendJson(ex, StatusCodes.BAD_REQUEST, JMap.of("error", "conversation id is required"))
              return
            }
            val body = mapper.readValue(bytes, classOf[JMap[_, _]])
            val message = body.get("message").asInstanceOf[String]
            if (message == null || message.isBlank) {
              sendJson(ex, StatusCodes.BAD_REQUEST, JMap.of("error", "message is required"))
              return
            }
            val requestAttachments = deserializeAttachments(body.get("attachments").asInstanceOf[java.util.List[_]])
            val modelIndex = parseModelIndex(body.get("modelIndex"))

            // SSE headers
            ex.getResponseHeaders.put(Headers.CONTENT_TYPE, "text/event-stream")
            ex.getResponseHeaders.put(Headers.CACHE_CONTROL, "no-cache")
            ex.getResponseHeaders.put(Headers.CONNECTION, "keep-alive")
            ex.setStatusCode(StatusCodes.OK)
            ex.startBlocking()
            val out: OutputStream = ex.getOutputStream

            val result = backend.chat(id, message, requestAttachments, modelIndex, new ChatStreamHandler {
              override def onToken(token: String): Unit = {
                try {
                  val sseData = "data: " + mapper.writeValueAsString(JMap.of("token", token)) + "\n\n"
                  out.write(sseData.getBytes(StandardCharsets.UTF_8))
                  out.flush()
                } catch {
                  case _: Exception => // client disconnected
                }
              }
            })

            // Send final event with result
            val done = new JHashMap[String, AnyRef]()
            done.put("done", java.lang.Boolean.TRUE)
            if (result.error != null) {
              done.put("error", result.error)
            }
            if (result.responseAttachments != null && result.responseAttachments.nonEmpty) {
              done.put("attachments", result.responseAttachments.map(serializeAttachment).asJava)
            }
            val sseEnd = "data: " + mapper.writeValueAsString(done) + "\n\n"
            out.write(sseEnd.getBytes(StandardCharsets.UTF_8))
            out.flush()
            ex.endExchange()
          } catch {
            case e: Exception =>
              sendJson(ex, StatusCodes.INTERNAL_SERVER_ERROR, JMap.of("error", e.getMessage))
          }
        }
      })
    })
  }

  // ----------------------------------------------------------------
  // Model endpoints (read-only)
  // ----------------------------------------------------------------

  private def getModels(exchange: HttpServerExchange): Unit = {
    val models = backend.getAvailableModels
    val modelList: java.util.List[JMap[String, String]] = models.map { m =>
      JMap.of("providerType", m.providerType, "model", m.model, "displayName", m.getDisplayName)
    }.asJava
    val result = new JHashMap[String, AnyRef]()
    result.put("models", modelList)
    sendJson(exchange, StatusCodes.OK, result)
  }

  // ----------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------

  private def parseModelIndex(value: AnyRef): Int = {
    if (value == null) return 0
    value match {
      case n: Number => n.intValue()
      case s: String =>
        try { s.toInt } catch { case _: NumberFormatException => 0 }
      case _ => 0
    }
  }

  private def conversationId(exchange: HttpServerExchange): String = {
    val qp = exchange.getQueryParameters.get("id")
    if (qp != null) {
      val value = qp.getFirst
      if (value != null && !value.isBlank) {
        return value
      }
    }
    val path = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY)
    if (path == null) {
      null
    } else {
      path.getParameters.get("id")
    }
  }

  private def serializeMessage(msg: ConversationMessage): JMap[String, AnyRef] = {
    val map = new JLinkedHashMap[String, AnyRef]()
    map.put("id", msg.id)
    map.put("type", messageType(msg))
    map.put("text", messageText(msg))
    map.put("archived", java.lang.Boolean.valueOf(msg.archived))
    map.put("createdAt", msg.createdAt.toString)
    if (msg.attachments != null && msg.attachments.nonEmpty) {
      map.put("attachments", msg.attachments.map(serializeAttachment).asJava)
    }
    map
  }

  private def serializeAttachment(a: MessageAttachment): JMap[String, String] = {
    val map = new JLinkedHashMap[String, String]()
    if (a.attachmentType != null) map.put("type", a.attachmentType)
    if (a.url != null) map.put("url", a.url)
    if (a.mimeType != null) map.put("mimeType", a.mimeType)
    if (a.title != null) map.put("title", a.title)
    map
  }

  private def deserializeAttachments(list: java.util.List[_]): List[MessageAttachment] = {
    if (list == null) {
      return Nil
    }
    val result = scala.collection.mutable.ArrayBuffer.empty[MessageAttachment]
    for (obj <- list.asScala) {
      obj match {
        case m: java.util.Map[_, _] =>
          val map = m.asInstanceOf[java.util.Map[String, AnyRef]]
          val attachmentType = firstNonBlank(
            valueAsString(map.get("type")),
            valueAsString(map.get("attachmentType"))
          )
          val url = valueAsString(map.get("url"))
          if (url != null && !url.isBlank) {
            result.append(MessageAttachment(
              attachmentType,
              url,
              valueAsString(map.get("mimeType")),
              valueAsString(map.get("title"))
            ))
          }
        case _ => // ignore malformed entries
      }
    }
    result.toList
  }

  private def valueAsString(value: AnyRef): String = {
    if (value == null) null else value.toString
  }

  private def firstNonBlank(values: String*): String = {
    values.find(v => v != null && !v.isBlank).orNull
  }

  private def messageType(msg: ConversationMessage): String = {
    msg.message match {
      case _: dev.langchain4j.data.message.UserMessage   => "user"
      case _: dev.langchain4j.data.message.AiMessage     => "ai"
      case _: dev.langchain4j.data.message.SystemMessage  => "system"
      case _                                              => "unknown"
    }
  }

  private def messageText(msg: ConversationMessage): String = {
    msg.message match {
      case u: dev.langchain4j.data.message.UserMessage   => u.singleText()
      case a: dev.langchain4j.data.message.AiMessage     => a.text()
      case s: dev.langchain4j.data.message.SystemMessage  => s.text()
      case other                                          => other.toString
    }
  }

  private def sendJson(exchange: HttpServerExchange, statusCode: Int, body: AnyRef): Unit = {
    exchange.setStatusCode(statusCode)
    exchange.getResponseHeaders.put(Headers.CONTENT_TYPE, "application/json")
    try {
      exchange.getResponseSender.send(mapper.writeValueAsString(body))
    } catch {
      case _: Exception =>
        exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR)
        exchange.getResponseSender.send("{\"error\":\"serialization failed\"}")
    }
  }

  private def notFound(exchange: HttpServerExchange): Unit = {
    sendJson(exchange, StatusCodes.NOT_FOUND, JMap.of("error", "not found"))
  }
}
