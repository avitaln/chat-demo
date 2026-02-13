package com.chatdemo.backend.document

import java.util.Locale
import scala.collection.mutable

/**
 * Lightweight lexical retriever for document chunks.
 */
class LexicalChunkRetriever {

  def retrieve(question: String, chunks: List[String], limit: Int): List[String] = {
    if (chunks == null || chunks.isEmpty || limit <= 0) {
      return Nil
    }

    val normalizedQuestion = normalize(question)
    val terms = normalizedQuestion.split("\\s+").toSet
    val scored = mutable.ArrayBuffer.empty[(String, Double)]

    for (chunk <- chunks) {
      val normalizedChunk = normalize(chunk)
      if (normalizedChunk.nonEmpty) {
        val score = scoreChunk(terms, normalizedQuestion, normalizedChunk)
        if (score > 0) {
          scored.append((chunk, score))
        }
      }
    }

    if (scored.isEmpty) {
      return chunks.take(limit)
    }

    scored.sortBy(-_._2).take(limit).map(_._1).toList
  }

  private def scoreChunk(terms: Set[String], normalizedQuestion: String, normalizedChunk: String): Double = {
    val freqs = frequencies(normalizedChunk)
    var score = 0.0
    for (term <- terms) {
      if (term.nonEmpty) {
        score += freqs.getOrElse(term, 0)
      }
    }
    if (normalizedQuestion.nonEmpty && normalizedChunk.contains(normalizedQuestion)) {
      score += 5
    }
    score
  }

  private def frequencies(text: String): Map[String, Int] = {
    val map = mutable.HashMap.empty[String, Int]
    for (word <- text.split("\\s+")) {
      if (word.nonEmpty) {
        map.updateWith(word) {
          case Some(count) => Some(count + 1)
          case None        => Some(1)
        }
      }
    }
    map.toMap
  }

  private def normalize(value: String): String = {
    if (value == null) {
      ""
    } else {
      value.toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9\\s]", " ")
        .replaceAll("\\s+", " ")
        .trim
    }
  }
}
