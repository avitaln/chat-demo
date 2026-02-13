package com.chatdemo.backend.document

import com.chatdemo.common.document.{DocumentArtifacts, FetchedDocument}

import java.io.IOException
import java.util.Locale
import scala.collection.mutable.ArrayBuffer

/**
 * Resolves compact prompt context from document URLs.
 */
class DocumentContextService(
  private val artifactCache: DocumentArtifactCache,
  private val documentFetcher: DocumentFetcher,
  private val textExtractor: DocumentTextExtractor,
  private val chunkRetriever: LexicalChunkRetriever
) {

  private val ChunkSize = 1200
  private val ChunkOverlap = 200
  private val RetrievalLimit = 4
  private val ContextCharBudget = 5000

  def this(artifactCache: DocumentArtifactCache) = {
    this(artifactCache, new HttpDocumentFetcher(), new DocumentTextExtractor(), new LexicalChunkRetriever())
  }

  def buildContext(sourceUrl: String, question: String): Option[String] = {
    if (sourceUrl == null || sourceUrl.isBlank) {
      return None
    }
    try {
      val chunks = getOrBuildChunks(sourceUrl)
      if (chunks.isEmpty) {
        return None
      }
      val relevant = chunkRetriever.retrieve(question, chunks, RetrievalLimit)
      val context = buildBoundedContext(relevant)
      if (context.isBlank) {
        None
      } else {
        Some(context)
      }
    } catch {
      case _: Exception => None
    }
  }

  private def getOrBuildChunks(sourceUrl: String): List[String] = {
    val cached = artifactCache.load(sourceUrl)
    if (cached.isDefined && cached.get.chunks != null && cached.get.chunks.nonEmpty) {
      return cached.get.chunks
    }
    val fetched = documentFetcher.fetch(sourceUrl)
    val text = textExtractor.extract(fetched)
    val chunks = splitText(text)
    if (chunks.nonEmpty) {
      artifactCache.save(sourceUrl, DocumentArtifacts(chunks))
    }
    chunks
  }

  def splitText(text: String): List[String] = {
    if (text == null) {
      return Nil
    }
    var normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    normalized = normalized.replaceAll("\\n{3,}", "\n\n").trim
    if (normalized.isBlank) {
      return Nil
    }

    val chunks = ArrayBuffer.empty[String]
    var start = 0
    while (start < normalized.length) {
      val end = Math.min(normalized.length, start + ChunkSize)
      var candidate = end
      if (end < normalized.length) {
        val boundary = findBoundary(normalized, start, end)
        if (boundary > start + ChunkSize / 2) {
          candidate = boundary
        }
      }
      val chunk = normalized.substring(start, candidate).trim
      if (chunk.nonEmpty) {
        chunks.append(chunk)
      }
      if (candidate >= normalized.length) {
        start = normalized.length // break
      } else {
        start = Math.max(0, candidate - ChunkOverlap)
      }
    }
    chunks.toList
  }

  def buildBoundedContext(chunks: List[String]): String = {
    if (chunks == null || chunks.isEmpty) {
      return ""
    }

    val sb = new StringBuilder()
    var used = 0
    var index = 1
    var done = false
    val it = chunks.iterator
    while (it.hasNext && !done) {
      val chunk = it.next()
      val clean = if (chunk == null) "" else chunk.trim
      if (clean.nonEmpty) {
        val line = s"[$index] $clean\n\n"
        if (used + line.length > ContextCharBudget) {
          val remaining = ContextCharBudget - used
          if (remaining > 50) {
            sb.append(line.substring(0, remaining))
          }
          done = true
        } else {
          sb.append(line)
          used += line.length
          index += 1
        }
      }
    }
    sb.toString().trim
  }

  private def findBoundary(text: String, start: Int, end: Int): Int = {
    var i = end
    while (i > start) {
      val c = text.charAt(i - 1)
      if (c == '.' || c == '!' || c == '?' || c == '\n') {
        return i
      }
      i -= 1
    }
    end
  }

  def isLikelyDocumentQuestion(text: String): Boolean = {
    if (text == null || text.isBlank) {
      return false
    }
    val lower = text.toLowerCase(Locale.ROOT)
    lower.contains("document") ||
      lower.contains("file") ||
      lower.contains("summary") ||
      lower.contains("summarize") ||
      lower.contains("what does") ||
      lower.contains("according to") ||
      lower.contains("in this") ||
      lower.contains("cv") ||
      lower.contains("resume")
  }
}
