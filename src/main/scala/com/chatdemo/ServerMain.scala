package com.chatdemo

import com.chatdemo.backend.DefaultChatBackend
import com.chatdemo.backend.server.ChatRoutes
import com.chatdemo.common.service.ChatBackend
import io.undertow.Undertow
import java.util.concurrent.CountDownLatch

/**
 * Entry point for the HTTP server process.
 * Start with: sbt "runMain com.chatdemo.ServerMain"
 */
object ServerMain {

  def main(args: Array[String]): Unit = {
    var port = 3000
    if (args.length > 0) {
      try {
        port = Integer.parseInt(args(0))
      } catch {
        case _: NumberFormatException => // keep default
      }
    }

    val backend: ChatBackend = new DefaultChatBackend()
    val routes = new ChatRoutes(backend)

    val server = Undertow.builder()
      .addHttpListener(port, "0.0.0.0")
      .setHandler(routes.buildHandler())
      .build()

    server.start()
    println(s"Chat server started on http://localhost:$port")

    // Keep the process alive until it is interrupted (Ctrl+C / SIGTERM).
    val shutdownLatch = new CountDownLatch(1)
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      try server.stop()
      catch {
        case _: Exception => // ignore shutdown errors
      } finally {
        shutdownLatch.countDown()
      }
    }))
    shutdownLatch.await()
  }
}
