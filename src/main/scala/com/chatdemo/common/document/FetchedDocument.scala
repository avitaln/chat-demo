package com.chatdemo.common.document

/**
 * Raw bytes and metadata downloaded from a source URL.
 */
case class FetchedDocument(sourceUrl: String, bytes: Array[Byte], contentType: String)
