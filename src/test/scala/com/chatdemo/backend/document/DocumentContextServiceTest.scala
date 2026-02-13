package com.chatdemo.backend.document

import com.chatdemo.common.document.{DocumentArtifacts, FetchedDocument}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.IOException

class DocumentContextServiceTest extends AnyFunSuite with Matchers {

  test("buildContext returns relevant chunk") {
    val cache = new InMemoryCache()
    val fetcher: DocumentFetcher = (url: String) => FetchedDocument(
      url,
      ("Alice has 8 years of backend engineering experience.\n" +
        "She worked with Java and distributed systems.\n" +
        "Bob likes painting.\n").getBytes,
      "text/plain"
    )
    val service = new DocumentContextService(
      cache,
      fetcher,
      new DocumentTextExtractor(),
      new LexicalChunkRetriever()
    )

    val context = service.buildContext("https://example.com/resume.txt", "How many years of backend experience?")

    context shouldBe defined
    context.get.toLowerCase should include("backend engineering experience")
  }

  test("buildContext returns empty on fetch failure") {
    val cache = new InMemoryCache()
    val fetcher: DocumentFetcher = (_: String) => {
      throw new IOException("boom")
    }
    val service = new DocumentContextService(
      cache,
      fetcher,
      new DocumentTextExtractor(),
      new LexicalChunkRetriever()
    )

    val context = service.buildContext("https://example.com/file.pdf", "Summarize")

    context shouldBe empty
  }

  test("bounded context respects character budget") {
    val service = new DocumentContextService(
      new NoopDocumentArtifactCache(),
      (url: String) => FetchedDocument(url, "x".getBytes, "text/plain"),
      new DocumentTextExtractor(),
      new LexicalChunkRetriever()
    )
    val chunks = List(
      "a" * 2500,
      "b" * 2500,
      "c" * 2500
    )

    val context = service.buildBoundedContext(chunks)

    context should not be empty
    context.length should be <= 5000
  }

  private class InMemoryCache extends DocumentArtifactCache {
    private var artifacts: DocumentArtifacts = _

    override def load(sourceUrl: String): Option[DocumentArtifacts] = Option(artifacts)

    override def save(sourceUrl: String, artifacts: DocumentArtifacts): Unit = {
      this.artifacts = artifacts
    }
  }
}
