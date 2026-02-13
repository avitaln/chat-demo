package com.chatdemo.backend.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.Firestore
import com.google.cloud.storage.Storage
import com.google.firebase.cloud.{FirestoreClient, StorageClient}
import com.google.firebase.{FirebaseApp, FirebaseOptions}

import java.io.FileInputStream
import java.nio.file.{Files, Path}

/**
 * Initializes Firebase Admin SDK once and provides Firestore/Storage clients.
 */
class FirebaseInitializer(serviceAccountPathStr: String, storageBucket: String) {

  private val serviceAccountPath: Path = Path.of(serviceAccountPathStr)
  private val appName = "chat-demo-backend"
  @volatile private var app: FirebaseApp = _

  def isConfigured: Boolean = Files.exists(serviceAccountPath)

  def firestore: Firestore = {
    val firebaseApp = initialize()
    FirestoreClient.getFirestore(firebaseApp)
  }

  def storage: Storage = {
    StorageClient.getInstance(initialize()).bucket().getStorage()
  }

  def resolvedBucketName: String = {
    if (storageBucket != null && !storageBucket.isBlank) {
      storageBucket
    } else {
      val firebaseApp = initialize()
      val configured = firebaseApp.getOptions.getStorageBucket
      if (configured != null && !configured.isBlank) configured else null
    }
  }

  private def initialize(): FirebaseApp = {
    var current = app
    if (current != null) {
      return current
    }
    this.synchronized {
      if (app != null) {
        return app
      }

      val existingNamed = FirebaseApp.getApps.stream()
        .filter(a => appName == a.getName)
        .findFirst()
      if (existingNamed.isPresent) {
        app = existingNamed.get()
        return app
      }

      val optionsBuilder = FirebaseOptions.builder().setCredentials(loadCredentials())
      if (storageBucket != null && !storageBucket.isBlank) {
        optionsBuilder.setStorageBucket(storageBucket)
      }
      app = FirebaseApp.initializeApp(optionsBuilder.build(), appName)
      app
    }
  }

  private def loadCredentials(): GoogleCredentials = {
    if (!Files.exists(serviceAccountPath)) {
      throw new IllegalStateException("Firebase service account file not found: " + serviceAccountPath)
    }
    val inputStream = new FileInputStream(serviceAccountPath.toFile)
    try {
      GoogleCredentials.fromStream(inputStream)
    } finally {
      inputStream.close()
    }
  }
}
