package com.chatdemo.backend.document

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class GcsDocumentArtifactCacheTest extends AnyFunSuite with Matchers {

  test("canonicalize removes transient query params") {
    val cache = new GcsDocumentArtifactCache("firebase_settings.json", "bucket")

    val canonical = cache.canonicalizeUrl(
      "https://firebasestorage.googleapis.com/v0/b/x/o/file.pdf?alt=media&token=abc&expires=123&signature=zzz"
    )

    canonical shouldBe "https://firebasestorage.googleapis.com/v0/b/x/o/file.pdf?alt=media"
  }

  test("canonicalize keeps stable URL without query") {
    val cache = new GcsDocumentArtifactCache("firebase_settings.json", "bucket")

    val canonical = cache.canonicalizeUrl("https://example.com/docs/resume.pdf")

    canonical shouldBe "https://example.com/docs/resume.pdf"
    canonical should not be empty
  }
}
