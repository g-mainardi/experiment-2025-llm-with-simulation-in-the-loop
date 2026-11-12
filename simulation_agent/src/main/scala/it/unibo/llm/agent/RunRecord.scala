package it.unibo.llm.agent

import com.fasterxml.jackson.databind.ObjectMapper

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** One JSON file per run in `runs/`, read by the offline evaluation: keep it flat. Fields are documented in CLAUDE.md. */
object RunRecord {

  private val json = new ObjectMapper()

  private val fileTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

  /** Writes the record and returns its path. outcome is `answered`, `limit` or `error`. */
  def write(
      model: String,
      temperature: Double,
      maxRoundTrips: Int,
      task: String,
      pieces: List[String],
      startedAt: OffsetDateTime,
      outcome: String,
      error: Option[String],
      answer: Option[String],
      calls: Seq[ToolCall],
      cost: RetryingChatModel
  ): Path = {
    val record = json.createObjectNode()
    record.put("model", model)
    record.put("temperature", temperature)
    record.put("maxRoundTrips", maxRoundTrips)
    record.put("task", task)
    pieces.foreach(record.putArray("pieces").add)
    record.put("condition", "full")
    record.put("startedAt", startedAt.toString)
    record.put("durationSeconds", Duration.between(startedAt, OffsetDateTime.now()).toMillis / 1000.0)
    record.put("outcome", outcome)
    record.put("error", error.orNull)
    val compiles  = calls.filter(_.name == "compile_code")
    val simulates = calls.filter(_.name == "simulate_code")
    record.put("compileCalls", compiles.size)
    record.put("compileFailures", compiles.count(_.failed))
    record.put("simulateCalls", simulates.size)
    record.put("simulateFailures", simulates.count(_.failed))
    val parameters = record.putArray("simulateParameters")
    simulates.foreach(call => parameters.add(Option(call.arguments.get("parameters")).getOrElse(json.nullNode())))
    val finalCall = simulates.lastOption.orElse(compiles.lastOption)
    record.put("finalProgram", finalCall.flatMap(c => Option(c.arguments.get("program"))).map(_.asText()).orNull)
    record.put("answer", answer.orNull)
    record.put("inputTokens", cost.inputTokens)
    record.put("outputTokens", cost.outputTokens)
    record.put("modelRequests", cost.requests)
    record.put("retries", cost.retries)
    val path = Path.of("runs", s"${startedAt.format(fileTime)}-$task-$model.json")
    Files.createDirectories(path.getParent)
    json.writerWithDefaultPrettyPrinter().writeValue(path.toFile, record)
    path
  }

}
