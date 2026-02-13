package com.chatdemo.app

import com.chatdemo.common.service.ChatBackend

/**
 * CLI entry point - connects to the HTTP server via HttpChatBackend.
 * Usage: java com.chatdemo.app.AppMain [serverUrl]
 * Default server URL: http://localhost:3000
 */
object AppMain {

  def main(args: Array[String]): Unit = {
    val serverUrl = if (args.length > 0) args(0) else "http://localhost:3000"
    val backend: ChatBackend = new HttpChatBackend(serverUrl)
    new ChatApplication(backend).run()
  }
}
