package it.unibo.llm.exp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.math.RoundingMode
import scala.jdk.CollectionConverters.*
import scala.util.Using

object Summary {

  private val json = new ObjectMapper()

  private val header =
    "task,cell,runs,passed,successRate,answered,limit,error,meanRoundTrips,meanRequests,meanCompileCalls,meanSimulateCalls,meanInputTokens,meanOutputTokens,totalRequests,totalInputTokens,totalOutputTokens,meanSeconds,olaussonN,olaussonPassAtN"

  private val historicalOrder = List("base-none", "base-compile", "base-full", "bare-full")

  private def read(dir: Path, glob: String): List[(Path, JsonNode)] =
    Using.resource(Files.newDirectoryStream(dir, glob))(
      _.iterator.asScala.toList.sorted.map(p => p -> json.readTree(p.toFile))
    )

  case class Cost(runs: Int, requests: Long, input: Long, output: Long, seconds: BigDecimal) {

    def +(that: Cost): Cost =
      Cost(runs + that.runs, requests + that.requests, input + that.input, output + that.output, seconds + that.seconds)

  }

  def cost(records: List[JsonNode]): Cost = {
    def total(field: String) = records.map(_.required(field).longValue()).sum
    Cost(
      records.size,
      total("modelRequests"),
      total("inputTokens"),
      total("outputTokens"),
      records.map(r => BigDecimal(r.required("durationSeconds").decimalValue())).sum
    )
  }

  def extraCost(dir: Path): Cost = cost(if (Files.isDirectory(dir)) read(dir, "*.json").map(_._2) else Nil)

  // Match the historical CSV: ties to even, and integral means of counts have no decimal point.
  private def rounded(value: Double, places: Int): Double =
    new java.math.BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).doubleValue()

  case class Row(task: String, cell: String, records: List[JsonNode]) {

    val totals = cost(records)

    val n = records.size

    val passed = records.count(_.required("evaluation").required("passed").booleanValue())

    private def mean(total: Long, places: Int): String =
      if (total % n == 0) (total / n).toString else rounded(total.toDouble / n, places).toString

    private def sum(field: String): Long = records.map(_.required(field).longValue()).sum

    private def outcome(name: String) = records.count(_.required("outcome").asText() == name)

    private val success = mean(passed, 3)

    private val requests = mean(totals.requests, 2)

    def csv(baseFull: Option[Row]): String = {
      val budget   = baseFull.filter(_ => cell == "base-none" && task != "all").map(_.requests)
      val baseline = budget.map(n => rounded(1 - math.pow(1 - success.toDouble, n.toDouble), 3))
      List(
        task,
        cell,
        n,
        passed,
        success,
        outcome("answered"),
        outcome("limit"),
        outcome("error"),
        mean(totals.requests - sum("retries") - n, 2),
        requests,
        mean(sum("compileCalls"), 2),
        mean(sum("simulateCalls"), 2),
        math.rint(totals.input.toDouble / n).toLong,
        math.rint(totals.output.toDouble / n).toLong,
        totals.requests,
        totals.input,
        totals.output,
        rounded((totals.seconds / n).toDouble, 1),
        budget.getOrElse(""),
        baseline.mkString
      )
        .map(_.toString)
        .map { field =>
          if (field.exists(",\"\r\n".contains(_))) "\"" + field.replace("\"", "\"\"") + "\"" else field
        }
        .mkString(",")
    }

  }

  def write(dir: Path): List[Row] = {
    val entries = read(dir, "*--*.json").map { case (path, record) =>
      require(record.hasNonNull("evaluation"), s"$path has no evaluation. Run Evaluate first.")
      (record.required("task").asText(), path.getFileName.toString.split("--", 2).head, record)
    }
    val cells   = entries.map(_._2).distinct
    val ordered = historicalOrder.filter(cells.contains) ++ cells.filterNot(historicalOrder.contains).sorted
    val groups  = entries.groupMap(e => e._1 -> e._2)(_._3).map { case ((t, c), rs) => Row(t, c, rs) }.toList
    val rows    = (groups ++ entries.groupMap(_._2)(_._3).map((c, rs) => Row("all", c, rs)))
      .sortBy(r => (r.task == "all", r.task, ordered.indexOf(r.cell)))
    val full = rows.filter(_.cell == "base-full").map(r => r.task -> r).toMap
    val csv  = (header :: rows.map(r => r.csv(full.get(r.task)))).mkString("", "\r\n", "\r\n")
    val tmp  = Files.createTempFile(dir, ".summary-", ".tmp")
    try {
      Files.writeString(tmp, csv)
      Files.move(tmp, dir.resolve("summary.csv"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally Files.deleteIfExists(tmp)
    print(csv)
    val errors = extraCost(dir.resolve("errors"))
    if (errors.runs > 0) println(s"${errors.runs} infrastructure failures used ${errors.requests} additional requests")
    groups.sortBy(r => (r.task, ordered.indexOf(r.cell)))
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Usage: exp.summary <directory>")
    write(Path.of(args.head))
  }

}
