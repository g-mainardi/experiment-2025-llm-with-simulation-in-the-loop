package it.unibo.llm.agent

import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel
import dev.langchain4j.model.ollama.OllamaChatModel

import java.time.OffsetDateTime

object Main {

  private val logger = org.slf4j.LoggerFactory.getLogger("SimulationAgentMain")

  private val DefaultMaxRoundTrips = 10

  private val Usage =
    s"""Usage: simulation_agent.run <model> <task id> [--option=value ...]
       |  --pieces=a,b,...       prompt pieces, comma separated (default ${Tasks.DefaultPieces.mkString(",")})
       |  --max-round-trips=n    rounds of tool calls before the run stops (default $DefaultMaxRoundTrips)""".stripMargin

  private def usageError(message: String): Nothing = {
    logger.error("{}\n{}\nTasks: {}", message, Usage, Tasks.all.map(_.id).mkString(", "))
    sys.exit(2)
  }

  def main(args: Array[String]): Unit = {
    val (flags, positional) = args.toList.partition(_.startsWith("--"))
    val options             = flags.map { flag =>
      flag.drop(2).split("=", 2) match {
        case Array(name, value) => name -> value
        case _                  => usageError(s"Option $flag needs a value")
      }
    }.toMap
    (options.keySet -- Set("pieces", "max-round-trips")).foreach(name => usageError(s"Unknown option --$name"))
    val (modelName, taskId) = positional match {
      case List(model, task) => (model, task)
      case _                 => usageError("Expected a model and a task id")
    }
    val pieces = options.get("pieces").fold(Tasks.DefaultPieces)(_.split(",").map(_.trim).filter(_.nonEmpty).toList)
    pieces.filterNot(Tasks.pieceExists) match {
      case Nil     => ()
      case missing =>
        usageError(s"No prompt piece ${missing.mkString(", ")} in simulation_agent/src/main/resources/prompts")
    }
    val maxRoundTrips = options.get("max-round-trips").fold(DefaultMaxRoundTrips) { value =>
      value.toIntOption.filter(_ > 0).getOrElse(usageError(s"--max-round-trips must be a positive integer: $value"))
    }
    val agentTask = Tasks.byId(taskId).getOrElse(usageError(s"Unknown task $taskId"))
    logger.info(
      "Starting simulation agent with model {} on task {}, prompt pieces {}, at most {} round trips", modelName, taskId,
      pieces.mkString(","), maxRoundTrips
    )
    val temperature             = 0.0
    val logRequestsAndResponses = true
    // Ollama goes through RetryingChatModel too, which counts requests and tokens for the run record
    val model = new RetryingChatModel(
      if (modelName.toLowerCase.startsWith("gemini"))
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
      else
        OllamaChatModel
          .builder()
          .temperature(temperature)
          .logRequests(logRequestsAndResponses)
          .logResponses(logRequestsAndResponses)
          .modelName(modelName)
          .baseUrl("http://localhost:11434")
          .build()
    )
    val task                       = new AgentTask(model, agentTask.fullPrompt(pieces), maxRoundTrips)
    val startedAt                  = OffsetDateTime.now()
    val (outcome, answer, failure) =
      try {
        val answer = task.execute()
        logger.info("Agent result: {}", answer)
        ("answered", Some(answer), None)
      } catch {
        case e: Exception if AgentTask.exceededRoundTrips(e) =>
          logger.warn("Agent stopped: no final answer after {} tool calling round trips", maxRoundTrips)
          ("limit", None, None)
        case e: Exception =>
          val message = RetryingChatModel.geminiError(e).map(_.path("message").asText()).getOrElse(e.getMessage)
          logger.error("Agent failed: {}", message)
          logger.debug("Agent failure", e)
          ("error", None, Some(message))
      }
    val record = RunRecord.write(
      modelName, temperature, maxRoundTrips, taskId, pieces, startedAt, outcome, failure, answer, task.calls.toSeq,
      model
    )
    logger.info("Run record: {} ({} model requests)", record, model.requests)
    if (outcome == "error") sys.exit(1)
  }

}
