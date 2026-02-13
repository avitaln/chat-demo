package com.chatdemo.backend

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets

class DefaultChatBackendImageAccessPolicyTest extends AnyFunSuite with Matchers {

  private val backendSourcePath: Path = Path.of(
    "src",
    "main",
    "scala",
    "com",
    "chatdemo",
    "backend",
    "DefaultChatBackend.scala"
  )

  private def backendSource: String = {
    Files.readString(backendSourcePath, StandardCharsets.UTF_8)
  }

  test("non-premium hard deny message is removed") {
    val source = backendSource
    source should not include "Image generation and image editing are available for premium users only."
    source should not include "shouldRejectImageRequestForNonPremium("
  }

  test("image tool selection still uses free and premium policy models") {
    val source = backendSource
    source should include("override def premiumImageModel")
    source should include("override def freeImageModel")
    source should include("val selectedModel = if (isPremium) premiumImageModel else freeImageModel")
  }
}
