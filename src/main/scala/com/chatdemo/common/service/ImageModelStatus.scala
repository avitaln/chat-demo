package com.chatdemo.common.service

/**
 * Snapshot of current image model configuration.
 */
case class ImageModelStatus(
  currentProvider: String,
  currentModelName: String,
  openAiModelName: String,
  geminiModelName: String,
  grokModelName: String,
  defaultGeminiModelName: String
)
