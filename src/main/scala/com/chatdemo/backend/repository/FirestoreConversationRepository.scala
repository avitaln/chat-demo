package com.chatdemo.backend.repository

import com.chatdemo.backend.config.FirebaseInitializer
import com.chatdemo.common.model.{Conversation, ConversationMessage, MessageAttachment, MessageAttachmentExtractor, UserContext}
import com.google.cloud.Timestamp
import com.google.cloud.firestore.{DocumentReference, DocumentSnapshot, FieldValue, Firestore, SetOptions}
import dev.langchain4j.data.message.{AiMessage, ChatMessage, SystemMessage, UserMessage}

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/**
 * Firestore-backed implementation of ConversationRepository.
 * Stores all conversations under /conversations with strict owner checks.
 */
class FirestoreConversationRepository(firebase: FirebaseInitializer) extends ConversationRepository {

  private val ConversationsCollection = "conversations"
  private val MessagesCollection = "messages"
  private val OwnerCacheTtlMs = 60000L
  private val ownerCheckCache = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Long]()

  override def createConversation(userContext: UserContext, conversationId: String): Boolean = {
    timedDb("createConversation", conversationId, userContext) {
      val created = new AtomicBoolean(false)
      val conversationRef = conversationDoc(conversationId)
      db.runTransaction[Void](tx => {
        val snapshot = tx.get(conversationRef).get()
        if (!snapshot.exists()) {
          tx.set(conversationRef, conversationCreateMap(userContext, conversationId), SetOptions.merge())
          created.set(true)
        } else {
          ensureOwner(userContext, conversationId, snapshot)
        }
        null
      }).get()
      markOwnerChecked(userContext, conversationId)
      created.get()
    }
  }

  override def listConversations(userContext: UserContext): List[Conversation] = {
    timedDb("listConversations", "-", userContext) {
      val docs = db.collection(ConversationsCollection)
        .whereEqualTo("ownerUserId", userContext.effectiveId)
        .orderBy("createdAt")
        .get()
        .get()
        .getDocuments
        .asScala

      docs.map { doc =>
        val id = doc.getId
        val title = nonNullTitle(doc.getString("title"), id)
        Conversation(id, title, timestampMillis(doc.get("createdAt")))
      }.toList
    }
  }

  override def setConversationTitle(userContext: UserContext, conversationId: String, title: String): Unit = {
    val conversationRef = conversationDoc(conversationId)
    db.runTransaction[Void](tx => {
      val snapshot = tx.get(conversationRef).get()
      ensureOwner(userContext, conversationId, snapshot)
      tx.update(conversationRef, "title", nonNullTitle(title, conversationId))
      tx.update(conversationRef, "updatedAt", FieldValue.serverTimestamp())
      null
    }).get()
  }

  override def getFullHistory(userContext: UserContext, conversationId: String): List[ConversationMessage] = {
    timedDb("getFullHistory", conversationId, userContext) {
      ensureOwnerAllowed(userContext, conversationId)
      val docs = messagesCollection(conversationId)
        .orderBy("createdAt")
        .get()
        .get()
        .getDocuments
        .asScala
      docs.map(toConversationMessage).toList
    }
  }

  override def getActiveMessages(userContext: UserContext, conversationId: String): List[ChatMessage] = {
    timedDb("getActiveMessages", conversationId, userContext) {
      ensureOwnerAllowed(userContext, conversationId)
      val docs = messagesCollection(conversationId)
        .whereEqualTo("archived", false)
        .orderBy("createdAt")
        .get()
        .get()
        .getDocuments
        .asScala
      docs.map(deserializeMessage).toList
    }
  }

  override def getSummary(userContext: UserContext, conversationId: String): String = {
    timedDb("getSummary", conversationId, userContext) {
      val snapshot = conversationDoc(conversationId).get().get()
      ensureOwner(userContext, conversationId, snapshot)
      markOwnerChecked(userContext, conversationId)
      snapshot.getString("summary")
    }
  }

  override def addMessage(userContext: UserContext, conversationId: String, message: ChatMessage): Unit = {
    timedDb("addMessage", conversationId, userContext) {
      val cm = new ConversationMessage(message, MessageAttachmentExtractor.extract(message))
      val conversationRef = conversationDoc(conversationId)
      val messageRef = messagesCollection(conversationId).document(cm.id)
      val messageTypeValue = messageType(message)

      db.runTransaction[Void](tx => {
        val snapshot = tx.get(conversationRef).get()
        if (!snapshot.exists()) {
          tx.set(conversationRef, conversationCreateMap(userContext, conversationId), SetOptions.merge())
        } else {
          ensureOwner(userContext, conversationId, snapshot)
        }

        tx.set(messageRef, messageMap(cm, messageTypeValue))
        val updates = new java.util.LinkedHashMap[String, AnyRef]()
        updates.put("updatedAt", FieldValue.serverTimestamp())
        if ("user" == messageTypeValue) updates.put("latestUserMessageId", cm.id)
        if ("ai" == messageTypeValue) updates.put("latestAiMessageId", cm.id)
        tx.set(conversationRef, updates, SetOptions.merge())
        null
      }).get()
      markOwnerChecked(userContext, conversationId)
    }
  }

  override def attachToLatestAiMessage(userContext: UserContext, conversationId: String, attachments: List[MessageAttachment]): Unit = {
    attachToLatestMessage(userContext, conversationId, attachments, "latestAiMessageId")
  }

  override def attachToLatestUserMessage(userContext: UserContext, conversationId: String, attachments: List[MessageAttachment]): Unit = {
    attachToLatestMessage(userContext, conversationId, attachments, "latestUserMessageId")
  }

  override def archiveMessages(userContext: UserContext, conversationId: String, messageIds: List[String], newSummary: String): Unit = {
    if (messageIds == null || messageIds.isEmpty) return
    timedDb("archiveMessages", conversationId, userContext) {
      val conversationRef = conversationDoc(conversationId)
      db.runTransaction[Void](tx => {
        val snapshot = tx.get(conversationRef).get()
        ensureOwner(userContext, conversationId, snapshot)
        for (messageId <- messageIds) {
          tx.update(messagesCollection(conversationId).document(messageId), "archived", java.lang.Boolean.TRUE)
        }
        val updates = new java.util.LinkedHashMap[String, AnyRef]()
        updates.put("summary", newSummary)
        updates.put("summaryUpdatedAt", FieldValue.serverTimestamp())
        updates.put("updatedAt", FieldValue.serverTimestamp())
        tx.set(conversationRef, updates, SetOptions.merge())
        null
      }).get()
      markOwnerChecked(userContext, conversationId)
    }
  }

  override def clear(userContext: UserContext, conversationId: String): Unit = {
    timedDb("clearConversation", conversationId, userContext) {
      ensureOwnedConversation(userContext, conversationId)
      val docs = messagesCollection(conversationId).listDocuments().iterator().asScala.toList
      if (docs.nonEmpty) {
        val batch = db.batch()
        for (doc <- docs) {
          batch.delete(doc)
        }
        batch.commit().get()
      }

      val updates = new java.util.LinkedHashMap[String, AnyRef]()
      updates.put("summary", null)
      updates.put("summaryUpdatedAt", null)
      updates.put("latestUserMessageId", null)
      updates.put("latestAiMessageId", null)
      updates.put("updatedAt", FieldValue.serverTimestamp())
      conversationDoc(conversationId).set(updates, SetOptions.merge()).get()
      markOwnerChecked(userContext, conversationId)
    }
  }

  private def attachToLatestMessage(
    userContext: UserContext,
    conversationId: String,
    attachments: List[MessageAttachment],
    latestField: String
  ): Unit = {
    if (attachments == null || attachments.isEmpty) return

    timedDb("attachToLatestMessage", conversationId, userContext) {
      val conversationRef = conversationDoc(conversationId)
      db.runTransaction[Void](tx => {
        val conversation = tx.get(conversationRef).get()
        ensureOwner(userContext, conversationId, conversation)

        val messageId = conversation.getString(latestField)
        if (messageId != null && !messageId.isBlank) {
          val messageRef = messagesCollection(conversationId).document(messageId)
          val messageSnapshot = tx.get(messageRef).get()
          if (messageSnapshot.exists()) {
            val existing = decodeAttachments(messageSnapshot.get("attachments"))
            val merged = mergeAttachments(existing, attachments)
            tx.update(messageRef, "attachments", merged.map(attachmentToMap).asJava)
            tx.update(conversationRef, "updatedAt", FieldValue.serverTimestamp())
          }
        }
        null
      }).get()
      markOwnerChecked(userContext, conversationId)
    }
  }

  private def toConversationMessage(doc: DocumentSnapshot): ConversationMessage = {
    val chatMessage = deserializeMessage(doc)
    val archived = Option(doc.getBoolean("archived")).contains(true)
    val createdAt = timestampMillis(doc.get("createdAt"))
    val attachments = decodeAttachments(doc.get("attachments"))
    new ConversationMessage(doc.getId, chatMessage, attachments, archived, createdAt)
  }

  private def messageMap(cm: ConversationMessage, messageTypeValue: String): java.util.Map[String, AnyRef] = {
    val map = new java.util.LinkedHashMap[String, AnyRef]()
    map.put("messageType", messageTypeValue)
    map.put("messageText", messageText(cm.message))
    map.put("archived", java.lang.Boolean.valueOf(cm.archived))
    map.put("createdAt", FieldValue.serverTimestamp())
    map.put("attachments", cm.attachments.map(attachmentToMap).asJava)
    map
  }

  private def conversationCreateMap(userContext: UserContext, conversationId: String): java.util.Map[String, AnyRef] = {
    val map = new java.util.LinkedHashMap[String, AnyRef]()
    map.put("ownerUserId", userContext.effectiveId)
    map.put("title", conversationId)
    map.put("createdAt", FieldValue.serverTimestamp())
    map.put("updatedAt", FieldValue.serverTimestamp())
    map
  }

  private def ensureOwnedConversation(userContext: UserContext, conversationId: String): DocumentSnapshot = {
    val snapshot = conversationDoc(conversationId).get().get()
    ensureOwner(userContext, conversationId, snapshot)
    markOwnerChecked(userContext, conversationId)
    snapshot
  }

  private def ensureOwnerAllowed(userContext: UserContext, conversationId: String): Unit = {
    if (isOwnerCheckCached(userContext, conversationId)) {
      return
    }
    val snapshot = conversationDoc(conversationId).get().get()
    ensureOwner(userContext, conversationId, snapshot)
    markOwnerChecked(userContext, conversationId)
  }

  private def ensureOwner(userContext: UserContext, conversationId: String, snapshot: DocumentSnapshot): Unit = {
    if (!snapshot.exists()) {
      throw new IllegalArgumentException("Conversation not found: " + conversationId)
    }
    val owner = snapshot.getString("ownerUserId")
    if (owner == null || owner != userContext.effectiveId) {
      throw new SecurityException("Forbidden conversation access")
    }
  }

  private def conversationDoc(conversationId: String): DocumentReference = {
    db.collection(ConversationsCollection).document(conversationId)
  }

  private def messagesCollection(conversationId: String) = {
    conversationDoc(conversationId).collection(MessagesCollection)
  }

  private def deserializeMessage(doc: DocumentSnapshot): ChatMessage = {
    val msgType = doc.getString("messageType")
    val text = nonNullText(doc.getString("messageText"))
    msgType match {
      case "user"   => UserMessage.from(text)
      case "ai"     => AiMessage.from(text)
      case "system" => SystemMessage.from(text)
      case _        => UserMessage.from(text)
    }
  }

  private def messageType(msg: ChatMessage): String = {
    msg match {
      case _: UserMessage   => "user"
      case _: AiMessage     => "ai"
      case _: SystemMessage => "system"
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

  private def nonNullText(value: String): String = {
    if (value == null) "" else value
  }

  private def nonNullTitle(value: String, fallback: String): String = {
    if (value == null || value.isBlank) fallback else value
  }

  private def decodeAttachments(raw: Any): List[MessageAttachment] = {
    raw match {
      case list: java.util.List[?] =>
        list.asScala.flatMap {
          case m: java.util.Map[?, ?] =>
            val map = m.asInstanceOf[java.util.Map[String, AnyRef]]
            val url = valueAsString(map.get("url"))
            if (url == null || url.isBlank) {
              None
            } else {
              Some(MessageAttachment(
                valueAsString(map.get("type")),
                url,
                valueAsString(map.get("mimeType")),
                valueAsString(map.get("title"))
              ))
            }
          case _ => None
        }.toList
      case _ => Nil
    }
  }

  private def mergeAttachments(existing: List[MessageAttachment], incoming: List[MessageAttachment]): List[MessageAttachment] = {
    val merged = ArrayBuffer.from(if (existing == null) Nil else existing)
    for (attachment <- incoming if attachment != null && attachment.url != null) {
      val duplicate = merged.exists(a =>
        a != null &&
          a.url == attachment.url &&
          normalize(a.attachmentType) == normalize(attachment.attachmentType)
      )
      if (!duplicate) {
        merged.append(attachment)
      }
    }
    merged.toList
  }

  private def attachmentToMap(attachment: MessageAttachment): java.util.Map[String, AnyRef] = {
    val map = new java.util.LinkedHashMap[String, AnyRef]()
    if (attachment.attachmentType != null) map.put("type", attachment.attachmentType)
    if (attachment.url != null) map.put("url", attachment.url)
    if (attachment.mimeType != null) map.put("mimeType", attachment.mimeType)
    if (attachment.title != null) map.put("title", attachment.title)
    map
  }

  private def valueAsString(value: AnyRef): String = {
    if (value == null) null else value.toString
  }

  private def normalize(value: String): String = {
    if (value == null) "" else value.trim.toLowerCase
  }

  private def timestampMillis(timestampValue: Any): Long = {
    timestampValue match {
      case timestamp: Timestamp => timestamp.toDate.getTime
      case n: java.lang.Number  => n.longValue()
      case s: String =>
        try {
          java.lang.Long.parseLong(s)
        } catch {
          case _: NumberFormatException => 0L
        }
      case _ => 0L
    }
  }

  private def db: Firestore = {
    firebase.firestore
  }

  private def ownerCacheKey(userContext: UserContext, conversationId: String): String = {
    userContext.effectiveId + "|" + conversationId
  }

  private def isOwnerCheckCached(userContext: UserContext, conversationId: String): Boolean = {
    val cachedUntil = ownerCheckCache.get(ownerCacheKey(userContext, conversationId))
    cachedUntil != null && cachedUntil.longValue() > System.currentTimeMillis()
  }

  private def markOwnerChecked(userContext: UserContext, conversationId: String): Unit = {
    ownerCheckCache.put(
      ownerCacheKey(userContext, conversationId),
      java.lang.Long.valueOf(System.currentTimeMillis() + OwnerCacheTtlMs)
    )
  }

  private def timedDb[T](operation: String, conversationId: String, userContext: UserContext)(block: => T): T = {
    val startedAt = System.nanoTime()
    try {
      block
    } finally {
      val elapsedMs = (System.nanoTime() - startedAt) / 1000000L
      println(s"[perf] firestore/$operation conversationId=$conversationId user=${userContext.effectiveId} took ${elapsedMs}ms")
    }
  }
}
