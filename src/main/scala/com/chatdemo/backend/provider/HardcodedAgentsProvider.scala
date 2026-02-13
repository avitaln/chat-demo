package com.chatdemo.backend.provider

class HardcodedAgentsProvider extends AgentsProvider {

  private val defaultPrompt: String =
    "You are a helpful assistant. Respond clearly and concisely. " +
      "If the user asks to create, draw, generate, edit, or modify images, you MUST call the generate_image tool. " +
      "IMPORTANT: Do NOT attempt to generate or create images yourself. You MUST use the generate_image tool for ALL image requests. " +
      "When the user wants to modify a previously generated image, call generate_image with a complete prompt that describes the full desired result including the requested changes. " +
      "For normal knowledge and reasoning questions, answer directly without image tools. " +
      "Return tool outputs directly when they satisfy the request."

  private val agents: Map[String, AgentConfig] = Map(
    "default" -> AgentConfig(
      id = "default",
      systemPrompt = defaultPrompt
    )
  )

  override def getAgent(id: String): Option[AgentConfig] = agents.get(id)
}
