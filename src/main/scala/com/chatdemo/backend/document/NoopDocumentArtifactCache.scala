package com.chatdemo.backend.document

import com.chatdemo.common.document.DocumentArtifacts

/**
 * Fallback cache that stores nothing.
 */
class NoopDocumentArtifactCache extends DocumentArtifactCache {

  override def load(sourceUrl: String): Option[DocumentArtifacts] = None

  override def save(sourceUrl: String, artifacts: DocumentArtifacts): Unit = {
    // no-op
  }
}
