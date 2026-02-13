package com.chatdemo.backend.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.{Acl, BlobId, BlobInfo, Storage, StorageOptions}

import java.io.{FileInputStream, IOException}
import java.nio.file.{Files, Path}
import java.util.{Locale, UUID}

/**
 * Uploads images to Firebase Storage and returns public URLs.
 */
class FirebaseStorageUploader(serviceAccountPathStr: String, configuredBucket: String) {

  private val serviceAccountPath: Path = Path.of(serviceAccountPathStr)
  private val objectMapper = new ObjectMapper()
  @volatile private var storage: Storage = _

  def isConfigured: Boolean = Files.exists(serviceAccountPath)

  def uploadBase64(bytes: Array[Byte], mimeType: String, provider: String, modelName: String): String = {
    try {
      val client = getStorageClient()
      val bucketName = resolveBucketName()
      if (bucketName == null || bucketName.isBlank) {
        throw new IllegalStateException("Missing Firebase Storage bucket name.")
      }
      val objectPath = buildObjectPath(provider, modelName, mimeType)
      val blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, objectPath))
        .setContentType(mimeType)
        .build()
      val blob = client.create(blobInfo, bytes)
      blob.createAcl(Acl.of(Acl.User.ofAllUsers(), Acl.Role.READER))
      s"https://storage.googleapis.com/$bucketName/$objectPath"
    } catch {
      case e: Exception =>
        throw new RuntimeException("Failed to upload image: " + e.getMessage, e)
    }
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

  private def buildObjectPath(provider: String, modelName: String, mimeType: String): String = {
    val safeProvider = sanitize(provider)
    val safeModel = sanitize(modelName)
    val extension = extensionForMimeType(mimeType)
    s"images/$safeProvider/$safeModel/${UUID.randomUUID()}$extension"
  }

  private def sanitize(input: String): String = {
    if (input == null) "unknown"
    else input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_")
  }

  private def extensionForMimeType(mimeType: String): String = {
    if (mimeType == null) {
      return ".png"
    }
    mimeType.toLowerCase(Locale.ROOT) match {
      case "image/jpeg" | "image/jpg" => ".jpg"
      case "image/webp"               => ".webp"
      case "image/gif"                => ".gif"
      case "image/bmp"                => ".bmp"
      case _                          => ".png"
    }
  }
}
