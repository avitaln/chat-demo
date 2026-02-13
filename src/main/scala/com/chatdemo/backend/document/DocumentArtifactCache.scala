package com.chatdemo.backend.document

import com.chatdemo.common.document.DocumentArtifacts

trait DocumentArtifactCache {
  def load(sourceUrl: String): Option[DocumentArtifacts]
  def save(sourceUrl: String, artifacts: DocumentArtifacts): Unit
}
