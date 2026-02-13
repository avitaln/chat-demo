package com.chatdemo.backend.repository

import com.chatdemo.common.model.{Conversation, ConversationMessage, MessageAttachment, MessageAttachmentExtractor, UserContext}
import dev.langchain4j.data.message.{AiMessage, ChatMessage, SystemMessage, UserMessage}

import java.sql.*
import scala.collection.mutable.ArrayBuffer

/**
 * SQLite-backed implementation of ConversationRepository.
 * Thread-safe - each method obtains its own JDBC connection from DriverManager.
 */
class SqliteConversationRepository(dbPath: String) extends ConversationRepository {

  private val jdbcUrl: String = "jdbc:sqlite:" + dbPath

  initSchema()

  // ----------------------------------------------------------------
  // Schema
  // ----------------------------------------------------------------

  private def initSchema(): Unit = {
    val conn = connection()
    try {
      val stmt = conn.createStatement()
      try {
        stmt.execute(
          """CREATE TABLE IF NOT EXISTS conversations (
            |    id TEXT PRIMARY KEY,
            |    user_id TEXT,
            |    title TEXT,
            |    summary TEXT,
            |    summary_updated_at TEXT,
            |    created_at TEXT NOT NULL
            |)""".stripMargin)
        // Migration: add user_id column if missing (existing DBs)
        try {
          stmt.execute("ALTER TABLE conversations ADD COLUMN user_id TEXT")
        } catch {
          case _: SQLException => // column already exists
        }
        // Migration: add title column if missing (existing DBs)
        try {
          stmt.execute("ALTER TABLE conversations ADD COLUMN title TEXT")
        } catch {
          case _: SQLException => // column already exists
        }
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_conversations_user ON conversations(user_id)")
        stmt.execute(
          """CREATE TABLE IF NOT EXISTS messages (
            |    id TEXT PRIMARY KEY,
            |    conversation_id TEXT NOT NULL,
            |    message_type TEXT NOT NULL,
            |    message_text TEXT NOT NULL,
            |    archived INTEGER NOT NULL DEFAULT 0,
            |    created_at TEXT NOT NULL,
            |    FOREIGN KEY (conversation_id) REFERENCES conversations(id)
            |)""".stripMargin)
        stmt.execute(
          """CREATE TABLE IF NOT EXISTS attachments (
            |    id INTEGER PRIMARY KEY AUTOINCREMENT,
            |    message_id TEXT NOT NULL,
            |    type TEXT,
            |    url TEXT,
            |    mime_type TEXT,
            |    title TEXT,
            |    FOREIGN KEY (message_id) REFERENCES messages(id)
            |)""".stripMargin)
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conversation_id, created_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_attachments_msg ON attachments(message_id)")
      } finally {
        stmt.close()
      }
    } catch {
      case e: SQLException =>
        throw new RuntimeException("Failed to initialize SQLite schema", e)
    } finally {
      conn.close()
    }
  }

  private def connection(): Connection = {
    val conn = DriverManager.getConnection(jdbcUrl)
    val s = conn.createStatement()
    try {
      s.execute("PRAGMA journal_mode=WAL")
      s.execute("PRAGMA foreign_keys=ON")
    } finally {
      s.close()
    }
    conn
  }

  // ----------------------------------------------------------------
  // Conversation CRUD
  // ----------------------------------------------------------------

  override def createConversation(userContext: UserContext, conversationId: String): Boolean = {
    val sql = "INSERT OR IGNORE INTO conversations (id, user_id, title, created_at) VALUES (?, ?, ?, ?)"
    val conn = connection()
    try {
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, conversationId)
        ps.setString(2, userContext.effectiveId)
        ps.setString(3, conversationId)
        ps.setString(4, System.currentTimeMillis().toString)
        ps.executeUpdate() > 0
      } finally {
        ps.close()
      }
    } catch {
      case e: SQLException => throw new RuntimeException(e)
    } finally {
      conn.close()
    }
  }

  override def listConversations(userContext: UserContext): List[Conversation] = {
    val sql = "SELECT id, title, created_at FROM conversations WHERE user_id = ? ORDER BY created_at"
    val conn = connection()
    try {
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, userContext.effectiveId)
        val rs = ps.executeQuery()
        val conversations = ArrayBuffer.empty[Conversation]
        while (rs.next()) {
          val id = rs.getString("id")
          val title = nonNullTitle(rs.getString("title"), id)
          val createdAt = parseCreatedAtMillis(rs.getString("created_at"))
          conversations.append(Conversation(id, title, createdAt))
        }
        conversations.toList
      } finally {
        ps.close()
      }
    } catch {
      case e: SQLException => throw new RuntimeException(e)
    } finally {
      conn.close()
    }
  }

  override def setConversationTitle(userContext: UserContext, conversationId: String, title: String): Unit = {
    val sql = "UPDATE conversations SET title = ? WHERE id = ? AND user_id = ?"
    val conn = connection()
    try {
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, nonNullTitle(title, conversationId))
        ps.setString(2, conversationId)
        ps.setString(3, userContext.effectiveId)
        ps.executeUpdate()
      } finally {
        ps.close()
      }
    } catch {
      case e: SQLException => throw new RuntimeException(e)
    } finally {
      conn.close()
    }
  }

  // ----------------------------------------------------------------
  // Messages
  // ----------------------------------------------------------------

  override def getFullHistory(userContext: UserContext, conversationId: String): List[ConversationMessage] = {
    val sql = "SELECT id, message_type, message_text, archived, created_at FROM messages WHERE conversation_id = ? ORDER BY created_at"
    val conn = connection()
    try {
      requireOwnedConversation(conn, userContext, conversationId)
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, conversationId)
        val rs = ps.executeQuery()
        val result = ArrayBuffer.empty[ConversationMessage]
        while (rs.next()) {
          val msgId = rs.getString("id")
          val chatMsg = deserializeMessage(rs.getString("message_type"), rs.getString("message_text"))
          val archived = rs.getInt("archived") == 1
          val createdAt = parseCreatedAtMillis(rs.getString("created_at"))
          val attachments = loadAttachments(conn, msgId)
          result.append(new ConversationMessage(msgId, chatMsg, attachments, archived, createdAt))
        }
        result.toList
      } finally {
        ps.close()
      }
    } catch {
      case e: SQLException => throw new RuntimeException(e)
    } finally {
      conn.close()
    }
  }

  override def getActiveMessages(userContext: UserContext, conversationId: String): List[ChatMessage] = {
    val sql = "SELECT message_type, message_text FROM messages WHERE conversation_id = ? AND archived = 0 ORDER BY created_at"
    val conn = connection()
    try {
      requireOwnedConversation(conn, userContext, conversationId)
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, conversationId)
        val rs = ps.executeQuery()
        val result = ArrayBuffer.empty[ChatMessage]
        while (rs.next()) {
          result.append(deserializeMessage(rs.getString("message_type"), rs.getString("message_text")))
        }
        result.toList
      } finally {
        ps.close()
      }
    } catch {
      case e: SQLException => throw new RuntimeException(e)
    } finally {
      conn.close()
    }
  }

  override def getSummary(userContext: UserContext, conversationId: String): String = {
    val sql = "SELECT summary FROM conversations WHERE id = ?"
    val conn = connection()
    try {
      requireOwnedConversation(conn, userContext, conversationId)
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, conversationId)
        val rs = ps.executeQuery()
        if (rs.next()) rs.getString("summary") else null
      } finally {
        ps.close()
      }
    } catch {
      case e: SQLException => throw new RuntimeException(e)
    } finally {
      conn.close()
    }
  }

  override def addMessage(userContext: UserContext, conversationId: String, message: ChatMessage): Unit = {
    ensureConversationExists(userContext, conversationId)
    val attachments = MessageAttachmentExtractor.extract(message)
    val cm = new ConversationMessage(message, attachments)

    val sql = "INSERT INTO messages (id, conversation_id, message_type, message_text, archived, created_at) VALUES (?, ?, ?, ?, 0, ?)"
    val conn = connection()
    try {
      conn.setAutoCommit(false)
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, cm.id)
        ps.setString(2, conversationId)
        ps.setString(3, messageType(message))
        ps.setString(4, messageText(message))
        ps.setString(5, cm.createdAt.toString)
        ps.executeUpdate()
      } finally {
        ps.close()
      }
      insertAttachments(conn, cm.id, attachments)
      conn.commit()
    } catch {
      case e: SQLException => throw new RuntimeException(e)
    } finally {
      conn.close()
    }
  }

  override def attachToLatestAiMessage(userContext: UserContext, conversationId: String, attachments: List[MessageAttachment]): Unit = {
    attachToLatestMessage(userContext, conversationId, attachments, "ai")
  }

  override def attachToLatestUserMessage(userContext: UserContext, conversationId: String, attachments: List[MessageAttachment]): Unit = {
    attachToLatestMessage(userContext, conversationId, attachments, "user")
  }

  private def attachToLatestMessage(
    userContext: UserContext,
    conversationId: String,
    attachments: List[MessageAttachment],
    msgType: String
  ): Unit = {
    if (attachments == null || attachments.isEmpty) return

    val sql = "SELECT id FROM messages WHERE conversation_id = ? AND message_type = ? ORDER BY created_at DESC LIMIT 1"
    val conn = connection()
    try {
      requireOwnedConversation(conn, userContext, conversationId)
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, conversationId)
        ps.setString(2, msgType)
        val rs = ps.executeQuery()
        if (rs.next()) {
          val msgId = rs.getString("id")
          val existing = loadAttachments(conn, msgId)
          val toInsert = attachments.filter { a =>
            a != null && a.url != null && !existing.exists { e =>
              e.url == a.url && normalize(e.attachmentType) == normalize(a.attachmentType)
            }
          }
          insertAttachments(conn, msgId, toInsert)
        }
      } finally {
        ps.close()
      }
    } catch {
      case e: SQLException => throw new RuntimeException(e)
    } finally {
      conn.close()
    }
  }

  override def archiveMessages(userContext: UserContext, conversationId: String, messageIds: List[String], newSummary: String): Unit = {
    if (messageIds.isEmpty) return
    val conn = connection()
    try {
      requireOwnedConversation(conn, userContext, conversationId)
      conn.setAutoCommit(false)
      val placeholders = messageIds.map(_ => "?").mkString(",")
      val sql = s"UPDATE messages SET archived = 1 WHERE id IN ($placeholders)"
      val ps = conn.prepareStatement(sql)
      try {
        for (i <- messageIds.indices) {
          ps.setString(i + 1, messageIds(i))
        }
        ps.executeUpdate()
      } finally {
        ps.close()
      }
      val ps2 = conn.prepareStatement("UPDATE conversations SET summary = ?, summary_updated_at = ? WHERE id = ?")
      try {
        ps2.setString(1, newSummary)
        ps2.setString(2, System.currentTimeMillis().toString)
        ps2.setString(3, conversationId)
        ps2.executeUpdate()
      } finally {
        ps2.close()
      }
      conn.commit()
    } catch {
      case e: SQLException => throw new RuntimeException(e)
    } finally {
      conn.close()
    }
  }

  override def clear(userContext: UserContext, conversationId: String): Unit = {
    val conn = connection()
    try {
      requireOwnedConversation(conn, userContext, conversationId)
      conn.setAutoCommit(false)
      val ps1 = conn.prepareStatement(
        "DELETE FROM attachments WHERE message_id IN (SELECT id FROM messages WHERE conversation_id = ?)")
      try {
        ps1.setString(1, conversationId)
        ps1.executeUpdate()
      } finally {
        ps1.close()
      }
      val ps2 = conn.prepareStatement("DELETE FROM messages WHERE conversation_id = ?")
      try {
        ps2.setString(1, conversationId)
        ps2.executeUpdate()
      } finally {
        ps2.close()
      }
      val ps3 = conn.prepareStatement("UPDATE conversations SET summary = NULL, summary_updated_at = NULL WHERE id = ?")
      try {
        ps3.setString(1, conversationId)
        ps3.executeUpdate()
      } finally {
        ps3.close()
      }
      conn.commit()
    } catch {
      case e: SQLException => throw new RuntimeException(e)
    } finally {
      conn.close()
    }
  }

  // ----------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------

  /** Ensures a conversation row exists for this user. Used by addMessage for safety. */
  private def ensureConversationExists(userContext: UserContext, conversationId: String): Unit = {
    val sql = "INSERT OR IGNORE INTO conversations (id, user_id, title, created_at) VALUES (?, ?, ?, ?)"
    val conn = connection()
    try {
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, conversationId)
        ps.setString(2, userContext.effectiveId)
        ps.setString(3, conversationId)
        ps.setString(4, System.currentTimeMillis().toString)
        ps.executeUpdate()
      } finally {
        ps.close()
      }
      requireOwnedConversation(conn, userContext, conversationId)
    } catch {
      case e: SQLException => throw new RuntimeException(e)
    } finally {
      conn.close()
    }
  }

  private def requireOwnedConversation(conn: Connection, userContext: UserContext, conversationId: String): Unit = {
    val sql = "SELECT user_id FROM conversations WHERE id = ?"
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, conversationId)
      val rs = ps.executeQuery()
      if (!rs.next()) {
        throw new IllegalArgumentException("Conversation not found: " + conversationId)
      }
      val owner = rs.getString("user_id")
      if (owner == null || owner != userContext.effectiveId) {
        throw new SecurityException("Forbidden conversation access")
      }
    } finally {
      ps.close()
    }
  }

  private def loadAttachments(conn: Connection, messageId: String): List[MessageAttachment] = {
    val sql = "SELECT type, url, mime_type, title FROM attachments WHERE message_id = ?"
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, messageId)
      val rs = ps.executeQuery()
      val result = ArrayBuffer.empty[MessageAttachment]
      while (rs.next()) {
        result.append(MessageAttachment(rs.getString("type"), rs.getString("url"), rs.getString("mime_type"), rs.getString("title")))
      }
      result.toList
    } finally {
      ps.close()
    }
  }

  private def insertAttachments(conn: Connection, messageId: String, attachments: List[MessageAttachment]): Unit = {
    if (attachments == null || attachments.isEmpty) return
    val sql = "INSERT INTO attachments (message_id, type, url, mime_type, title) VALUES (?, ?, ?, ?, ?)"
    val ps = conn.prepareStatement(sql)
    try {
      for (a <- attachments) {
        ps.setString(1, messageId)
        ps.setString(2, a.attachmentType)
        ps.setString(3, a.url)
        ps.setString(4, a.mimeType)
        ps.setString(5, a.title)
        ps.addBatch()
      }
      ps.executeBatch()
    } finally {
      ps.close()
    }
  }

  private def messageType(msg: ChatMessage): String = {
    msg match {
      case _: UserMessage   => "user"
      case _: AiMessage     => "ai"
      case _: SystemMessage  => "system"
      case _                => "unknown"
    }
  }

  private def messageText(msg: ChatMessage): String = {
    val raw = msg match {
      case u: UserMessage   => u.singleText()
      case a: AiMessage     => a.text()
      case s: SystemMessage => s.text()
      case other            => other.toString
    }
    nonNullText(raw)
  }

  private def deserializeMessage(msgType: String, text: String): ChatMessage = {
    val safeText = nonNullText(text)
    msgType match {
      case "user"   => UserMessage.from(safeText)
      case "ai"     => AiMessage.from(safeText)
      case "system" => SystemMessage.from(safeText)
      case _        => UserMessage.from(safeText)
    }
  }

  private def normalize(value: String): String = {
    if (value == null) "" else value.trim.toLowerCase
  }

  private def nonNullText(value: String): String = {
    if (value == null) "" else value
  }

  private def nonNullTitle(value: String, fallback: String): String = {
    if (value == null || value.isBlank) fallback else value
  }

  private def parseCreatedAtMillis(value: String): Long = {
    if (value == null || value.isBlank) {
      0L
    } else {
      try {
        java.lang.Long.parseLong(value)
      } catch {
        case _: NumberFormatException =>
          try {
            java.time.Instant.parse(value).toEpochMilli
          } catch {
            case _: Exception => 0L
          }
      }
    }
  }
}
