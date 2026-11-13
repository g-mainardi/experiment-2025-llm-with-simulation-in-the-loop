package it.unibo.llm.mcp.server

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.StreamConverters._
import scala.util.control.NonFatal

/** Judges the final program of each agent run record with `FormationOracle` and writes the result in the record, as
  * `evaluation` (documented in CLAUDE.md). The program is simulated in the oracle's scenario with the target radius,
  * whatever `parameters` the agent chose.
  *
  * Usage: `./mill mcp_server.runMain it.unibo.llm.mcp.server.Evaluate <runs dir> [--force]` (absolute path). `--force`
  * evaluates again the records that already have an evaluation, e.g. after a threshold change.
  */
object Evaluate {

  private val json = JsonMapper.builder().build()

  private val defaultRadius = utils.ScafiTestUtils.variables("radius").getDefault.toString.toDouble

  def main(args: Array[String]): Unit = {
    val (flags, positional) = args.toList.partition(_.startsWith("--"))
    val dir                 = positional match {
      case List(dir) if flags.forall(_ == "--force") && Files.isDirectory(Path.of(dir)) => Path.of(dir)
      case _                                                                            =>
        System.err.println("Usage: Evaluate <absolute path of the runs dir> [--force]")
        sys.exit(2)
    }
    val records = Files.list(dir).toScala(List).filter(_.toString.endsWith(".json")).sorted
    for (file <- records) {
      val record = json.readTree(file.toFile).asInstanceOf[ObjectNode]
      if (record.has("evaluation") && !flags.contains("--force")) println(s"${file.getFileName}: already evaluated")
      else {
        val evaluation = evaluate(record, file)
        record.set("evaluation", evaluation)
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile, record)
        println(s"${file.getFileName}: ${summary(evaluation)}")
      }
    }
  }

  private def evaluate(record: JsonNode, file: Path): ObjectNode = {
    val evaluation = json.createObjectNode()
    val target     = Option(record.get("target")).filterNot(_.isNull)
    val radius     = target.fold {
      println(s"${file.getFileName}: no target in the record, using the scenario radius $defaultRadius")
      defaultRadius
    }(_.get("radius").asDouble())
    evaluation.put("passed", false)
    evaluation.putNull("reason")
    evaluation.put("radius", radius)
    val criterion = evaluation.putObject("criterion")
    criterion.put("maxRmse", FormationOracle.RmseTolerance * radius)
    criterion.put("maxAngularGap", FormationOracle.AngularTolerance)
    criterion.put("minDistance", FormationOracle.DistanceTolerance * FormationOracle.collisionArea)
    criterion.put("steadyState", FormationOracle.SteadyState)
    criterion.put("endTime", FormationOracle.EndTime)
    criterion.put("jitter", FormationOracle.Jitter)
    val seeds   = evaluation.putArray("seeds")
    val program = Option(record.get("finalProgram")).filterNot(_.isNull).map(_.asString())
    val shape   = target.fold("circle")(_.get("shape").asString())
    val reason  =
      if (shape != "circle") Some(s"unsupported target shape $shape")
      else
        program.fold(Option("no final program")) { program =>
          // A compilation or simulation error is the same on every seed: stop at the first one
          FormationOracle.Seeds.iterator.map { seed =>
            val result =
              try FormationOracle.evaluate(program, radius, seed)
              catch { case NonFatal(e) => Left(List(e.toString)) }
            result.map(circle => seeds.add(metrics(seed, circle))).left.map(e => s"seed $seed: ${e.mkString("\n")}")
          }.collectFirst { case Left(error) => error }
        }
    reason match {
      case Some(text) => evaluation.put("reason", text)
      case None       => evaluation.put("passed", seeds.valueStream().toScala(List).forall(_.get("passed").asBoolean()))
    }
    evaluation
  }

  private def metrics(seed: Int, circle: FormationOracle.Circle): ObjectNode = {
    val node = json.createObjectNode()
    node.put("seed", seed)
    node.put("passed", circle.passed)
    node.put("meanRadius", circle.meanRadius)
    node.put("rmse", circle.rmse)
    node.put("angularGap", circle.angularGap)
    node.put("minDistance", circle.minDistance)
    circle.convergence.fold(node.putNull("convergence"))(node.put("convergence", _))
    node
  }

  private def summary(evaluation: JsonNode): String = {
    val seeds                                              = evaluation.get("seeds").valueStream().toScala(List)
    def worst(field: String, pick: List[Double] => Double) =
      if (seeds.isEmpty) "-" else f"${pick(seeds.map(_.get(field).asDouble()))}%.3f"
    val verdict = if (evaluation.get("passed").asBoolean()) "PASSED" else "failed"
    val reason  = Option(evaluation.get("reason")).filterNot(_.isNull).fold("")(r => s" (${r.asString()})")
    s"$verdict$reason; ${seeds.count(_.get("passed").asBoolean())}/${seeds.size} seeds passed, worst RMSE " +
      s"${worst("rmse", _.max)}, angular gap ${worst("angularGap", _.max)}, min distance ${worst("minDistance", _.min)}"
  }

}
