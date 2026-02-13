package com.chatdemo.common.model

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class MessageAttachmentExtractorTest extends AnyFunSuite with Matchers {

  test("fromUrl classifies document with query string") {
    val attachment = MessageAttachmentExtractor.fromUrl(
      "https://host/path/file.pdf?alt=media&token=abc"
    )

    attachment should not be null
    attachment.attachmentType shouldBe "document"
    attachment.mimeType shouldBe "application/pdf"
  }
}
