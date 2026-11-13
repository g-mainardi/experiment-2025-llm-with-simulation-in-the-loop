package it.unibo.llm.mcp.server

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import scala.collection.mutable
import scala.jdk.StreamConverters._
import scala.util.control.NonFatal

/** Judges the final program of each agent run record with `FormationOracle` and writes the result in the record, as
  * `evaluation` (documented in CLAUDE.md). The program is simulated in the oracle's scenario with the target radius,
  * whatever `parameters` the agent chose.
  *
  * Usage: `./mill mcp_server.runMain it.unibo.llm.mcp.server.Evaluate <runs dir> [--force] [--threads=n]` (absolute
  * path). `--force` evaluates again the records that already have an evaluation, e.g. after a threshold change.
  *
  * The seeds of all the programs run in parallel on `--threads` threads (default: half the logical cores; on a
  * 20-thread laptop 10 threads were as fast as 20, with half the CPU and memory). Each simulation has its own compiled
  * package, export dir and `ResourceLoader` (one per thread), and a seed gives the same run whatever runs alongside it:
  * the evaluations come out identical to sequential ones. Records with the same program and target share one
  * evaluation, simulated once; without `--force`, a program already judged in another record of the dir is not
  * simulated again.
  */
object Evaluate {

  private val json = JsonMapper.builder().build()

  private val defaultRadius = utils.ScafiTestUtils.variables("radius").getDefault.toString.toDouble

  private type Run = Future[Either[List[String], FormationOracle.Circle]]

  /** All an evaluation depends on: records with the same key get the same evaluation. */
  final private case class Key(program: Option[String], radius: Double, shape: String)

  def main(args: Array[String]): Unit = {
    val (flags, positional) = args.toList.partition(_.startsWith("--"))
    val force               = flags.contains("--force")
    val threads             = flags.collectFirst { case s"--threads=$n" => n.toIntOption.filter(_ > 0) }
    val valid               = flags.forall(f => f == "--force" || f.startsWith("--threads=")) && !threads.contains(None)
    val dir                 = positional match {
      case List(dir) if valid && Files.isDirectory(Path.of(dir)) => Path.of(dir)
      case _                                                     =>
        System.err.println("Usage: Evaluate <absolute path of the runs dir> [--force] [--threads=n]")
        sys.exit(2)
    }
    val files           = Files.list(dir).toScala(List).filter(_.toString.endsWith(".json")).sorted
    val records         = files.map(file => file -> json.readTree(file.toFile).asInstanceOf[ObjectNode])
    val (done, pending) = records.partition { case (_, record) => record.has("evaluation") && !force }
    done.foreach { case (file, _) => println(s"${file.getFileName}: already evaluated") }

    // Evaluations already in the dir, made with the current criterion. Only complete ones: a reason can be a timeout,
    // which depends on the load
    val known = done.flatMap { case (file, record) =>
      val evaluation = record.get("evaluation")
      val key        = keyOf(record, file, quiet = true)
      val current    = header(key.radius)
      val reusable   = evaluation.get("reason").isNull && evaluation.get("radius") == current.get("radius") &&
        evaluation.get("criterion") == current.get("criterion")
      Option.when(reusable)(key -> (file, evaluation))
    }.toMap

    val start = System.nanoTime()
    val pool  =
      Executors.newFixedThreadPool(threads.flatten.getOrElse((Runtime.getRuntime.availableProcessors / 2).max(1)))
    try {
      // Every distinct program is submitted at once, so the pool stays busy while the records are written in order
      val jobs = mutable.LinkedHashMap.empty[Key, () => JsonNode]
      val keys = pending.map { case (file, record) =>
        val key = keyOf(record, file, quiet = false)
        if (!known.contains(key)) jobs.getOrElseUpdate(key, submit(key, pool))
        key
      }
      val firstFile = mutable.Map.empty[Key, Path]
      for (((file, record), key) <- pending.zip(keys)) {
        val (evaluation, origin) = known.get(key) match {
          case Some((from, evaluation)) => (evaluation, Some(from))
          case None                     => (jobs(key)(), firstFile.get(key))
        }
        firstFile.getOrElseUpdate(key, file)
        record.set("evaluation", evaluation.deepCopy())
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile, record)
        val same = origin.fold("")(from => s" [same program as ${from.getFileName}]")
        println(s"${file.getFileName}: ${summary(evaluation)}$same")
      }
      val seconds = (System.nanoTime() - start) / 1e9
      println(f"${pending.size} records evaluated, ${jobs.size} distinct programs simulated, in $seconds%.0f s")
    } finally pool.shutdownNow()
  }

  private def keyOf(record: JsonNode, file: Path, quiet: Boolean): Key = {
    val target = Option(record.get("target")).filterNot(_.isNull)
    val radius = target.fold {
      if (!quiet) println(s"${file.getFileName}: no target in the record, using the scenario radius $defaultRadius")
      defaultRadius
    }(_.get("radius").asDouble())
    val program = Option(record.get("finalProgram")).filterNot(_.isNull).map(_.asString())
    Key(program, radius, target.fold("circle")(_.get("shape").asString()))
  }

  /** The fields that don't depend on the simulations, with a negative verdict and no seeds yet. */
  private def header(radius: Double): ObjectNode = {
    val evaluation = json.createObjectNode()
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
    // Records judged on another number of seeds keep saying so, and their evaluations are not reused
    criterion.put("seeds", FormationOracle.Seeds.size)
    evaluation.putArray("seeds")
    evaluation
  }

  /** Starts the simulations of `key` on the pool. The returned function waits for them; it is computed once. */
  private def submit(key: Key, pool: ExecutorService): () => JsonNode = {
    val evaluation             = header(key.radius)
    val result: () => JsonNode = (key.shape, key.program) match {
      case ("circle", Some(program)) =>
        val runs = FormationOracle.Seeds.map { seed =>
          seed -> pool.submit(new Callable[Either[List[String], FormationOracle.Circle]] {
            override def call(): Either[List[String], FormationOracle.Circle] =
              try FormationOracle.evaluate(program, key.radius, seed)
              catch { case NonFatal(e) => Left(List(e.toString)) }
          })
        }
        () => collect(evaluation, runs)
      case ("circle", None) => () => evaluation.put("reason", "no final program")
      case (shape, _)       => () => evaluation.put("reason", s"unsupported target shape $shape")
    }
    lazy val once = result()
    () => once
  }

  /** The seeds in order, as if run one after the other. A compilation or simulation error is the same on every seed:
    * the first one is the reason, the seeds before it keep their metrics and the ones after it are dropped.
    */
  private def collect(evaluation: ObjectNode, runs: List[(Int, Run)]): JsonNode = {
    val seeds  = evaluation.withArray("seeds")
    val reason = runs.iterator.map { case (seed, run) =>
      run.get().map(circle => seeds.add(metrics(seed, circle))).left.map(e => s"seed $seed: ${e.mkString("\n")}")
    }.collectFirst { case Left(error) => error }
    reason match {
      case Some(text) =>
        runs.foreach(_._2.cancel(false))
        evaluation.put("reason", text)
      case None => evaluation.put("passed", seeds.valueStream().toScala(List).forall(_.get("passed").asBoolean()))
    }
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
