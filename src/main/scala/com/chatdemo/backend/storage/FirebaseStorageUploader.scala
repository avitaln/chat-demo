package com.chatdemo.backend.storage

import com.chatdemo.backend.config.FirebaseInitializer
import com.google.cloud.storage.{Acl, BlobId, BlobInfo}

import java.util.{Locale, UUID}

/**
 * Uploads images to Firebase Storage and returns public URLs.
 */
class FirebaseStorageUploader(firebase: FirebaseInitializer) {

  def this(serviceAccountPathStr: String, configuredBucket: String) =
    this(new FirebaseInitializer(serviceAccountPathStr, configuredBucket))

  def isConfigured: Boolean = firebase.isConfigured

  def uploadBase64(bytes: Array[Byte], mimeType: String, provider: String, modelName: String): String = {
    try {
      val bucketName = firebase.resolvedBucketName
      if (bucketName == null || bucketName.isBlank) {
        throw new IllegalStateException("Missing Firebase Storage bucket name.")
      }
      val client = firebase.storage
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
