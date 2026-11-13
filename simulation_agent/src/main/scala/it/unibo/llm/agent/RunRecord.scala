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

  // Every fenced block, so the closing fence of a ```text block is not taken for an opening one
  private val codeBlock = "(?s)```([\\w+-]*)[ \\t]*\\r?\\n(.*?)```".r

  private val classDefinition = "\\bclass\\s+\\w+".r

  /** Without tools the program is only in the answer: its last code block tagged scala or untagged that defines a
    * class, the full program the goal asks to report; the model often repeats the changed lines in a later block.
    * Without such a block, the last code block.
    */
  def lastCodeBlock(answer: String): Option[String] = {
    val blocks = codeBlock.findAllMatchIn(answer).filter(m => Set("", "scala")(m.group(1))).map(_.group(2)).toList
    blocks.findLast(classDefinition.findFirstIn(_).isDefined).orElse(blocks.lastOption)
  }

  /** Writes the record and returns its path. outcome is `answered`, `limit` or `error`. */
  def write(
      model: String,
      temperature: Option[Double],
      maxRoundTrips: Int,
      task: Task,
      pieces: List[String],
      condition: String,
      startedAt: OffsetDateTime,
      outcome: String,
      error: Option[String],
      answer: Option[String],
      calls: Seq[ToolCall],
      cost: RetryingChatModel
  ): Path = {
    val record = json.createObjectNode()
    record.put("model", model)
    record.put("temperature", temperature.map(Double.box).orNull)
    record.put("maxRoundTrips", maxRoundTrips)
    record.put("task", task.id)
    // The offline evaluation needs the target and doesn't depend on this module
    val target = record.putObject("target")
    task.target match {
      case Target.Circle(radius) => target.put("shape", "circle").put("radius", radius)
    }
    pieces.foreach(record.putArray("pieces").add)
    record.put("condition", condition)
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
    val finalCall    = simulates.lastOption.orElse(compiles.lastOption)
    val finalProgram =
      if (condition == "none") answer.flatMap(lastCodeBlock)
      else finalCall.flatMap(c => Option(c.arguments.get("program"))).map(_.asText())
    record.put("finalProgram", finalProgram.orNull)
    record.put("answer", answer.orNull)
    record.put("inputTokens", cost.inputTokens)
    record.put("outputTokens", cost.outputTokens)
    record.put("modelRequests", cost.requests)
    record.put("retries", cost.retries)
    val path = Path.of("runs", s"${startedAt.format(fileTime)}-${task.id}-$model.json")
    Files.createDirectories(path.getParent)
    json.writerWithDefaultPrettyPrinter().writeValue(path.toFile, record)
    path
  }

}
