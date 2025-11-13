package it.unibo.llm.agent

import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel

import java.io.File

object Main {
  private val logger = org.slf4j.LoggerFactory.getLogger("SimulationAgentMain")
  def main(args: Array[String]): Unit = {
    val modelName = args(0)
    logger.info("Starting simulation agent with with model: {}", modelName)
    val model = GoogleAiGeminiChatModel.builder()
      .temperature(0.0)
      .logRequests(true)
      .logResponses(true)
      .modelName(modelName)
      .apiKey(System.getenv("GOOGLE_API_KEY"))
      .build()
    val task = new AgentTask(model, "Run a simulation of Alchemist and provide the final results.")
    val result = task.execute()
    logger.info("Agent result: {}", result)
  }
}
