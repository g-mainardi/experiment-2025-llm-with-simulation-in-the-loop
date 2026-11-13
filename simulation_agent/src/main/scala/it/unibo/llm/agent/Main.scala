package it.unibo.llm.agent

import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel
import dev.langchain4j.model.ollama.OllamaChatModel

object Main {

  private val logger = org.slf4j.LoggerFactory.getLogger("SimulationAgentMain")

  def main(args: Array[String]): Unit = {
    if (args.length < 2 || args.length > 3) {
      logger.error(
        "Usage: simulation_agent.run <model> <task id> [prompt pieces, comma separated]; tasks: {}",
        Tasks.all.map(_.id).mkString(", ")
      )
      sys.exit(2)
    }
    val modelName = args(0)
    val taskId    = args(1)
    val pieces    = args.lift(2).fold(Tasks.DefaultPieces)(_.split(",").map(_.trim).filter(_.nonEmpty).toList)
    pieces.filterNot(Tasks.pieceExists) match {
      case Nil     => ()
      case missing =>
        logger.error("No prompt piece {} in simulation_agent/src/main/resources/prompts", missing.mkString(", "))
        sys.exit(2)
    }
    val agentTask = Tasks.byId(taskId).getOrElse {
      logger.error("Unknown task {}; tasks: {}", taskId, Tasks.all.map(_.id).mkString(", "))
      sys.exit(2)
    }
    logger.info(
      "Starting simulation agent with model {} on task {}, prompt pieces {}",
      modelName,
      taskId,
      pieces.mkString(",")
    )
    val temperature             = 0.0
    val logRequestsAndResponses = true
    val model                   = if (modelName.toLowerCase.startsWith("gemini")) {
      new RetryingChatModel(
        GoogleAiGeminiChatModel
          .builder()
          .apiKey(System.getenv("GOOGLE_API_KEY"))
          .modelName(modelName)
          .temperature(temperature)
          // Gemini 3 rejects tool calls sent back without their thought signatures
          .returnThinking(true)
          .sendThinking(true)
          .maxRetries(0) // RetryingChatModel retries instead
          .logRequestsAndResponses(logRequestsAndResponses)
          .build()
      )
    } else {
      OllamaChatModel
        .builder()
        .temperature(temperature)
        .logRequests(logRequestsAndResponses)
        .logResponses(logRequestsAndResponses)
        .modelName(modelName)
        .baseUrl("http://localhost:11434")
        .build()
    }
    val task = new AgentTask(model, agentTask.fullPrompt(pieces))
    try logger.info("Agent result: {}", task.execute())
    catch {
      case e: Exception =>
        val message = RetryingChatModel.geminiError(e).map(_.path("message").asText()).getOrElse(e.getMessage)
        logger.error("Agent failed: {}", message)
        logger.debug("Agent failure", e)
        sys.exit(1)
    }
  }

}
