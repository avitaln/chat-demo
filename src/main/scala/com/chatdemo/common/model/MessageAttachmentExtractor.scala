package com.chatdemo.common.model

import dev.langchain4j.data.message.{AiMessage, ChatMessage, SystemMessage, UserMessage}

import java.util.Locale
import java.util.regex.Pattern
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * Extracts structured attachment metadata from plain message text.
 */
object MessageAttachmentExtractor {

  private val UrlPattern: Pattern = Pattern.compile("(https?://\\S+)")
  private val ImageUrlPrefix: String = "image url:"

  def extract(message: ChatMessage): List[MessageAttachment] = {
    val text = extractText(message)
    if (text == null || text.isBlank) {
      return Nil
    }
    extractFromText(text)
  }

  def extractFromText(text: String): List[MessageAttachment] = {
    val matcher = UrlPattern.matcher(text)
    val seen = mutable.LinkedHashSet.empty[String]
    val attachments = ArrayBuffer.empty[MessageAttachment]
    val lower = text.toLowerCase(Locale.ROOT)

    while (matcher.find()) {
      val url = sanitizeUrl(matcher.group(1))
      if (url != null && !url.isBlank && seen.add(url)) {
        val matchStart = matcher.start()
        val contextStart = Math.max(0, matchStart - 15)
        val context = lower.substring(contextStart, matchStart)
        val explicitImage = context.contains(ImageUrlPrefix)

        val attachmentType = detectType(url, explicitImage)
        val mimeType = inferMimeType(url, attachmentType)
        attachments.append(MessageAttachment(attachmentType, url, mimeType, null))
      }
    }
    attachments.toList
  }

  def fromUrl(url: String): MessageAttachment = {
    val sanitized = sanitizeUrl(url)
    if (sanitized == null || sanitized.isBlank) {
      return null
    }
    val attachmentType = detectType(sanitized, explicitImage = false)
    val mimeType = inferMimeType(sanitized, attachmentType)
    MessageAttachment(attachmentType, sanitized, mimeType, null)
  }

  private def extractText(message: ChatMessage): String = {
    message match {
      case ai: AiMessage     => ai.text()
      case user: UserMessage  => user.singleText()
      case sys: SystemMessage => sys.text()
      case _                  => null
    }
  }

  private def sanitizeUrl(url: String): String = {
    if (url == null) {
      return null
    }
    var sanitized = url.strip()
    while (sanitized.nonEmpty && isTrailingPunctuation(sanitized.last)) {
      sanitized = sanitized.substring(0, sanitized.length - 1)
    }
    sanitized
  }

  private def isTrailingPunctuation(c: Char): Boolean = {
    c == '.' || c == ',' || c == ';' || c == ')' || c == ']' || c == '}'
  }

  private def detectType(url: String, explicitImage: Boolean): String = {
    if (explicitImage || isImage(url)) {
      "image"
    } else if (isDocument(url)) {
      "document"
    } else {
      "link"
    }
  }

  private def isImage(url: String): Boolean = {
    val lower = basePath(url).toLowerCase(Locale.ROOT)
    lower.endsWith(".png") ||
      lower.endsWith(".jpg") ||
      lower.endsWith(".jpeg") ||
      lower.endsWith(".gif") ||
      lower.endsWith(".webp") ||
      lower.endsWith(".bmp") ||
      lower.endsWith(".svg")
  }

  private def isDocument(url: String): Boolean = {
    val lower = basePath(url).toLowerCase(Locale.ROOT)
    lower.endsWith(".pdf") ||
      lower.endsWith(".doc") ||
      lower.endsWith(".docx") ||
      lower.endsWith(".txt") ||
      lower.endsWith(".rtf") ||
      lower.endsWith(".csv") ||
      lower.endsWith(".xlsx") ||
      lower.endsWith(".xls") ||
      lower.endsWith(".ppt") ||
      lower.endsWith(".pptx")
  }

  private def inferMimeType(url: String, attachmentType: String): String = {
    val lower = basePath(url).toLowerCase(Locale.ROOT)
    attachmentType match {
      case "image" =>
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) "image/jpeg"
        else if (lower.endsWith(".gif")) "image/gif"
        else if (lower.endsWith(".webp")) "image/webp"
        else if (lower.endsWith(".bmp")) "image/bmp"
        else if (lower.endsWith(".svg")) "image/svg+xml"
        else "image/png"
      case "document" =>
        if (lower.endsWith(".pdf")) "application/pdf"
        else if (lower.endsWith(".csv")) "text/csv"
        else if (lower.endsWith(".txt")) "text/plain"
        else if (lower.endsWith(".doc")) "application/msword"
        else if (lower.endsWith(".docx")) "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else if (lower.endsWith(".xls")) "application/vnd.ms-excel"
        else if (lower.endsWith(".xlsx")) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else if (lower.endsWith(".ppt")) "application/vnd.ms-powerpoint"
        else if (lower.endsWith(".pptx")) "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else "application/octet-stream"
      case _ => "text/uri-list"
    }
  }

  private def basePath(url: String): String = {
    val query = url.indexOf('?')
    if (query >= 0) url.substring(0, query) else url
  }
}
