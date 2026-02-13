package com.chatdemo.backend.document

import com.chatdemo.common.document.DocumentArtifacts
import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.{BlobId, BlobInfo, Storage, StorageOptions}

import java.io.{FileInputStream, IOException}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.{HexFormat, Locale, List => JList}
import scala.jdk.CollectionConverters.*

/**
 * Stores parsed document artifacts in GCS without a DB.
 */
class GcsDocumentArtifactCache(
  serviceAccountPathStr: String,
  configuredBucket: String,
  namespace: String = "global"
) extends DocumentArtifactCache {

  private val CachePrefix = "doc_cache"
  private val serviceAccountPath: Path = Path.of(serviceAccountPathStr)
  private val resolvedNamespace: String = if (namespace == null || namespace.isBlank) "global" else namespace
  private val objectMapper = new ObjectMapper()
  @volatile private var storage: Storage = _

  def isConfigured: Boolean = Files.exists(serviceAccountPath)

  override def load(sourceUrl: String): Option[DocumentArtifacts] = {
    if (!isConfigured) {
      return None
    }
    try {
      val client = getStorageClient()
      val bucket = resolveBucketName()
      if (bucket == null || bucket.isBlank) {
        return None
      }
      val objectPath = objectPathFor(sourceUrl)
      val blob = client.get(BlobId.of(bucket, objectPath))
      if (blob == null || !blob.exists()) {
        return None
      }
      val bytes = blob.getContent()
      val chunks = objectMapper.readValue(bytes, new TypeReference[JList[String]]() {})
      Some(DocumentArtifacts(chunks.asScala.toList))
    } catch {
      case _: Exception => None
    }
  }

  override def save(sourceUrl: String, artifacts: DocumentArtifacts): Unit = {
    if (!isConfigured || artifacts == null || artifacts.chunks == null || artifacts.chunks.isEmpty) {
      return
    }
    try {
      val client = getStorageClient()
      val bucket = resolveBucketName()
      if (bucket == null || bucket.isBlank) {
        return
      }
      val objectPath = objectPathFor(sourceUrl)
      val javaChunks: JList[String] = artifacts.chunks.asJava
      val content = objectMapper.writeValueAsBytes(javaChunks)
      val blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectPath))
        .setContentType("application/json")
        .build()
      client.create(blobInfo, content)
    } catch {
      case _: Exception => // cache failures should not fail chat flow
    }
  }

  def canonicalizeUrl(sourceUrl: String): String = {
    if (sourceUrl == null) {
      return ""
    }
    val trimmed = sourceUrl.trim
    try {
      val uri = URI.create(trimmed)
      val scheme = if (uri.getScheme == null) "https" else uri.getScheme.toLowerCase(Locale.ROOT)
      val host = if (uri.getHost == null) "" else uri.getHost.toLowerCase(Locale.ROOT)
      val path = if (uri.getPath == null) "" else uri.getPath
      val query = uri.getQuery
      if (query == null || query.isBlank) {
        return s"$scheme://$host$path"
      }
      val filtered = filterQuery(query)
      if (filtered.isBlank) {
        s"$scheme://$host$path"
      } else {
        s"$scheme://$host$path?$filtered"
      }
    } catch {
      case _: Exception => trimmed
    }
  }

  private def filterQuery(query: String): String = {
    val result = new StringBuilder()
    for (pair <- query.split("&")) {
      if (pair.nonEmpty) {
        val key = if (pair.contains("=")) pair.substring(0, pair.indexOf('=')) else pair
        if (!key.equalsIgnoreCase("token") && !key.equalsIgnoreCase("signature") && !key.equalsIgnoreCase("expires")) {
          if (result.nonEmpty) {
            result.append('&')
          }
          result.append(pair)
        }
      }
    }
    result.toString()
  }

  private def objectPathFor(sourceUrl: String): String = {
    val canonical = canonicalizeUrl(sourceUrl)
    s"$CachePrefix/$resolvedNamespace/${sha256(canonical)}/chunks.json"
  }

  private def sha256(input: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(input.getBytes(StandardCharsets.UTF_8))
    HexFormat.of().formatHex(hash)
  }

  private def getStorageClient(): Storage = {
    var current = storage
    if (current != null) {
      return current
    }
    this.synchronized {
      if (storage != null) {
        return storage
      }
      val inputStream = new FileInputStream(serviceAccountPath.toFile)
      try {
        val credentials = GoogleCredentials.fromStream(inputStream)
        storage = StorageOptions.newBuilder()
          .setCredentials(credentials)
          .build()
          .getService
        storage
      } finally {
        inputStream.close()
      }
    }
  }

  private def resolveBucketName(): String = {
    if (configuredBucket != null && !configuredBucket.isBlank) {
      return configuredBucket
    }
    try {
      val root = objectMapper.readTree(serviceAccountPath.toFile)
      val projectId = root.get("project_id")
      if (projectId != null && !projectId.asText().isBlank) {
        return projectId.asText() + ".appspot.com"
      }
    } catch {
      case _: IOException => return null
    }
    null
  }
}
