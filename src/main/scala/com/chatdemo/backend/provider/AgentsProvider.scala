package com.chatdemo.backend.provider

case class AgentConfig(id: String, systemPrompt: String)

trait AgentsProvider {
  def getAgent(id: String): Option[AgentConfig]
}
