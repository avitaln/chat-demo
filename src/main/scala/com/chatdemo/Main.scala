package com.chatdemo

import com.chatdemo.app.{ChatApplication, HttpChatBackend}
import com.chatdemo.common.service.ChatBackend

/**
 * CLI entry point - connects to the HTTP server via HttpChatBackend.
 * Usage: java com.chatdemo.Main [serverUrl]
 * Default server URL: http://localhost:3000
 */
object Main {

  def main(args: Array[String]): Unit = {
    val serverUrl = if (args.length > 0) args(0) else "http://localhost:3000"
    val backend: ChatBackend = new HttpChatBackend(serverUrl)
    new ChatApplication(backend).run()
  }
}
