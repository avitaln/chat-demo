package com.chatdemo.backend.document

import com.chatdemo.common.document.FetchedDocument
import java.io.IOException

trait DocumentFetcher {
  @throws[IOException]
  def fetch(sourceUrl: String): FetchedDocument
}
