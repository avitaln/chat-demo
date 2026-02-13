package com.chatdemo.common.model

/**
 * Structured attachment metadata for UI/mobile rendering.
 */
case class MessageAttachment(
  attachmentType: String,
  url: String,
  mimeType: String,
  title: String
)
