package it.unibo.llm.exp

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import scala.jdk.CollectionConverters.*

object Report {

  private def percent(value: Double): String = String.format(java.util.Locale.ROOT, "%.1f%%", Double.box(value * 100))

  def wilson(passed: Int, runs: Int): (Double, Double) = {
    val z           = 1.959963984540054
    val p           = passed.toDouble / runs
    val denominator = 1 + z * z / runs
    val center      = p + z * z / (2 * runs)
    val margin      = z * math.sqrt((p * (1 - p) + z * z / (4 * runs)) / runs)
    (math.max(0, (center - margin) / denominator), math.min(1, (center + margin) / denominator))
  }

  private def quote(arg: String): String = "'" + arg.replace("'", "'\"'\"'") + "'"

  def write(
      dir: Path,
      directory: String,
      k: Int,
      jobs: Int,
      tasks: List[String],
      cells: List[String],
      rows: List[Summary.Row]
  ): Unit = {
    val readme = dir.resolve("README.md")
    if (!Files.exists(readme)) {
      val records                                    = rows.flatMap(_.records)
      val evaluations                                = records.map(_.required("evaluation"))
      def values(nodes: List[JsonNode], key: String) =
        nodes.map(r => Option(r.get(key)).filterNot(_.isNull).map(_.asText()).getOrElse("unrecorded")).distinct.sorted
      val runCommits  = values(records, "commit")
      val evalCommits = values(evaluations, "commit")
      val warnings    = List(
        Option.when((runCommits ++ evalCommits).filterNot(_ == "unrecorded").distinct.size > 1)(
          "The records reference multiple commits. Check which revision produced each run and evaluation."
        ),
        Option.when((records ++ evaluations).exists(_.path("dirty").asBoolean(false)))(
          "Some runs or evaluations used a dirty worktree. Document the local changes here."
        )
      ).flatten
      val warningSection = if (warnings.isEmpty) "" else warnings.mkString("\n\n", "\n\n", "")
      val criteria       = evaluations
        .map(e => Option(e.get("criterion")).filterNot(_.isNull).fold("unrecorded")(_.toString))
        .distinct
        .sorted
      val designs = rows
        .flatMap(row =>
          row.records.map { r =>
            val pieces = r.path("pieces").elements().asScala.map(_.asText()).mkString(", ")
            s"| ${row.cell} | ${r.required("condition").asText()} | ${if (pieces.isEmpty) "none" else pieces} |"
          }
        )
        .distinct
      val summaryRows =
        if (rows.map(_.task).distinct.size > 1) {
          val cellOrder = rows.map(_.cell).distinct
          rows ++ cellOrder.map(c => Summary.Row("all", c, rows.filter(_.cell == c).flatMap(_.records)))
        } else rows
      val results = summaryRows.map { row =>
        val (lo, hi) = wilson(row.passed, row.n)
        s"| ${row.task} | ${row.cell} | ${row.passed}/${row.n} | ${percent(row.passed.toDouble / row.n)} | ${percent(lo)}–${percent(hi)} |"
      }
      val zeroCost = Summary.Cost(0, 0, 0, 0, BigDecimal(0))
      val costs    = List(
        "Matrix" -> rows.map(_.totals).foldLeft(zeroCost)(_ + _),
        "Errors" -> Summary.extraCost(dir.resolve("errors")),
        "Pilot"  -> Summary.extraCost(dir.resolve("pilot"))
      )
      val costRows = (costs :+ ("Total" -> costs.map(_._2).foldLeft(zeroCost)(_ + _))).map { case (group, c) =>
        s"| $group | ${c.runs} | ${c.requests} | ${c.input} | ${c.output} | ${c.seconds.bigDecimal.toPlainString} |"
      }
      val command =
        s"./mill exp.matrix ${quote(directory)} -k $k --jobs $jobs --tasks ${tasks.mkString(",")} --cells ${cells.mkString(",")}"
      val content =
        s"""# Feedback comparison
           ~
           ~## Question
           ~
           ~## Design
           ~
           ~The matrix used ${values(records, "model").mkString(", ")} with a round-trip budget of ${values(
            records,
            "maxRoundTrips"
          ).mkString(", ")}. The records contain ${rows.map(_.n).distinct.sorted.mkString(", ")} runs per task and cell.
           ~
           ~The starting programs were ${rows.map(_.task).distinct.map(t => s"`tasks/$t.scala.txt`").mkString(", ")}.
           ~
           ~| Cell | Feedback | Prompt pieces |
           ~| --- | --- | --- |
           ~${designs.mkString("\n")}
           ~
           ~The oracle used ${criteria.map(c => s"`$c`").mkString(", ")}.
           ~
           ~## Code version and commands
           ~
           ~The run revisions were ${runCommits.mkString(", ")}. The evaluation revisions were ${evalCommits.mkString(
            ", "
          )}.$warningSection
           ~
           ~```bash
           ~$command
           ~```
           ~
           ~## Results
           ~
           ~| Task | Cell | Passed/runs | Success | 95% Wilson interval |
           ~| --- | --- | ---: | ---: | ---: |
           ~${results.mkString("\n")}
           ~
           ~### Tests between cells
           ~
           ~## Cost
           ~
           ~| Records | Runs | Model requests | Input tokens | Output tokens | Sum of run durations (s) |
           ~| --- | ---: | ---: | ---: | ---: | ---: |
           ~${costRows.mkString("\n")}
           ~
           ~## Data notes
           ~
           ~## Follow-up analyses
           ~
           ~## How to regenerate
           ~
           ~```bash
           ~./mill mcp_server.runMain it.unibo.llm.mcp.server.Evaluate ${quote(directory)} --force
           ~./mill exp.summary ${quote(directory)}
           ~```
           ~""".stripMargin('~')
      try Files.writeString(readme, content, StandardOpenOption.CREATE_NEW)
      catch { case _: FileAlreadyExistsException => () }
    }
  }

}
