package com.chatdemo.backend.tools

import com.chatdemo.common.model.MessageAttachment
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ImageGenerationToolAttachmentCollectorTest extends AnyFunSuite with Matchers {

  test("attachment collectors are isolated between sessions") {
    val premiumCollector = new ImageGenerationTool.AttachmentCollector()
    val freeCollector = new ImageGenerationTool.AttachmentCollector()

    premiumCollector.add(MessageAttachment("image", "https://premium.example/img.png", "image/png", null))
    freeCollector.add(MessageAttachment("image", "https://free.example/img.png", "image/png", null))

    val premiumDrain = premiumCollector.drain()
    val freeDrain = freeCollector.drain()

    premiumDrain.map(_.url) should contain only "https://premium.example/img.png"
    freeDrain.map(_.url) should contain only "https://free.example/img.png"
  }

  test("drain removes only drained collector data") {
    val collectorA = new ImageGenerationTool.AttachmentCollector()
    val collectorB = new ImageGenerationTool.AttachmentCollector()

    collectorA.add(MessageAttachment("image", "https://a.example/1.png", "image/png", null))
    collectorB.add(MessageAttachment("image", "https://b.example/1.png", "image/png", null))

    collectorA.drain().map(_.url) should contain only "https://a.example/1.png"
    collectorA.drain() shouldBe Nil
    collectorB.drain().map(_.url) should contain only "https://b.example/1.png"
  }
}
