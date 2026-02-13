package com.chatdemo.common.service

/**
 * Callback for streaming chat tokens from the backend.
 */
trait ChatStreamHandler {
  def onToken(token: String): Unit
}
