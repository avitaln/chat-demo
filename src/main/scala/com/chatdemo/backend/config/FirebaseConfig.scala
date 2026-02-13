package com.chatdemo.backend.config

/**
 * Configuration for Firebase Storage uploads.
 */
object FirebaseConfig {

  private val DefaultServiceAccountPath = "firebase_settings.json"

  def getServiceAccountPath: String = {
    val path = System.getenv("FIREBASE_SERVICE_ACCOUNT_PATH")
    if (path != null && !path.isBlank) {
      path
    } else {
      DefaultServiceAccountPath
    }
  }

  def getStorageBucket: String = {
    val bucket = System.getenv("FIREBASE_STORAGE_BUCKET")
    if (bucket != null && !bucket.isBlank) {
      bucket
    } else {
      null
    }
  }
}
