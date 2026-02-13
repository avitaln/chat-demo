package com.chatdemo.backend.provider

import com.chatdemo.common.config.ProviderConfig

trait ModelsProvider {
  def getAvailableModels: List[ProviderConfig]
}
