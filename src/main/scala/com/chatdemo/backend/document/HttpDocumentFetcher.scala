package com.chatdemo.backend.document

import com.chatdemo.common.document.FetchedDocument

import java.io.{ByteArrayOutputStream, IOException, InputStream}
import java.net.{HttpURLConnection, URL}

/**
 * Fetches document bytes from a public URL.
 */
class HttpDocumentFetcher extends DocumentFetcher {

  @throws[IOException]
  override def fetch(sourceUrl: String): FetchedDocument = {
    val url = new URL(sourceUrl)
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
      throw new IOException("Unable to download document: HTTP " + status)
    }
    val bytes = readAllBytes(inputStream)
    var contentType = connection.getContentType
    if (contentType != null) {
      val separator = contentType.indexOf(';')
      contentType = if (separator > 0) contentType.substring(0, separator).trim else contentType.trim
    }
    FetchedDocument(sourceUrl, bytes, contentType)
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
}
