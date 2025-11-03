package it.unibo.llm.agent

import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel

import java.io.File

object Main {
  private val logger = org.slf4j.LoggerFactory.getLogger("SimulationAgentMain")
  def main(args: Array[String]): Unit = {
    val pathToJar = args(0)
    val modelName = args(1)
    logger.info("Starting simulation agent with JAR: {} and model: {}", pathToJar, modelName)
    val model = GoogleAiGeminiChatModel.builder()
      .temperature(0.0)
      .logRequests(true)
      .logResponses(true)
      .modelName(modelName)
      .apiKey(System.getenv("GOOGLE_API_KEY"))
      .build()
    val task = new AgentTask(new File(pathToJar), model, "Run a simulation of Alchemist and provide the final results.")
    val result = task.execute()
    logger.info("Agent result: {}", result)
  }
}
