package it.unibo.llm.exp

import it.unibo.llm.agent.Tasks

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.Using

object Matrix {

  private case class Cell(name: String, condition: String, pieces: List[String])

  private def cell(name: String): Cell = name.split("-", 2).toList match {
    case prompt :: condition :: Nil if Tasks.Conditions.contains(condition) =>
      val pieces = prompt match {
        case "bare"                        => Nil
        case "base" if condition == "full" =>
          List("goal", "procedure-full", "scala3", "parameters", "classpath", "api", "simulate-first", "stop")
        case "base" => List("goal", s"procedure-$condition", "scala3", "classpath", "api")
        case _      => throw IllegalArgumentException(s"Unknown prompt in cell $name (expected base or bare)")
      }
      Cell(name, condition, pieces)
    case _ => throw IllegalArgumentException(s"Invalid cell $name (conditions: ${Tasks.Conditions.mkString(", ")})")
  }

  private def selection(value: String): List[String] = {
    val items = value.split(",", -1).map(_.trim).toList
    require(items.forall(_.nonEmpty) && items.distinct == items, s"Expected distinct, nonempty names: $value")
    items
  }

  private case class Run(task: String, cell: Cell, rep: Int) {

    val name = f"${cell.name}--$task--r$rep%02d"

  }

  private def execute(
      command: Seq[String],
      output: ProcessBuilder.Redirect,
      env: Map[String, String] = Map.empty
  ): Int = {
    val builder = new ProcessBuilder(command.asJava).redirectErrorStream(true).redirectOutput(output)
    builder.environment().putAll(env.asJava)
    val process = builder.start()
    try process.waitFor()
    finally
      if (process.isAlive) {
        process.descendants().forEach(_.destroyForcibly())
        process.destroyForcibly().onExit().join()
      }
  }

  private def runOne(agent: String, dir: Path, run: Run): Unit = {
    val log      = dir.resolve(s"logs/${run.name}")
    val attempt  = s"${run.name}-${UUID.randomUUID()}.json"
    val pending  = dir.resolve("pending").resolve(attempt)
    val pieceArg = Option.when(run.cell.pieces.nonEmpty)(s"--pieces=${run.cell.pieces.mkString(",")}")
    val args     = Seq(
      agent,
      "gemini-3.5-flash-lite",
      run.task,
      s"--condition=${run.cell.condition}",
      "--max-round-trips=20",
      s"--record=$pending"
    ) ++ pieceArg
    val status =
      execute(args, ProcessBuilder.Redirect.to(Path.of(s"$log.log").toFile), Map("AGENT_LOG" -> s"$log.debug.log"))
    if (Files.exists(pending)) {
      val target = if (status == 0) dir.resolve(s"${run.name}.json") else dir.resolve("errors").resolve(attempt)
      Files.move(pending, target, ATOMIC_MOVE)
    }
    if (status == 0 && Files.exists(dir.resolve(s"${run.name}.json"))) println(s"== ${run.name}")
    else System.err.println(s"!! ${run.name}: see $log.log")
  }

  /** Up to three passes over the runs still missing, read again before each pass, once the previous one has finished:
    * later passes retry only the runs without a record. (In a for-comprehension, `todo = missing()` after `pass <- 1 to
    * 3` would be computed for all three passes before the first one.)
    */
  private[exp] def passes[A](missing: () => Seq[A])(run: (Int, Seq[A]) => Unit): Unit =
    (1 to 3).foreach { pass =>
      val todo = missing()
      if (todo.nonEmpty) run(pass, todo)
    }

  def main(args: Array[String]): Unit = {
    val Array(agent, classpath, directory, repetitions, parallelism, taskNames, cellNames) = args: @unchecked
    val dir                                                                                = Path.of(directory).toAbsolutePath.normalize()
    val k                                                                                  = repetitions.toInt
    val jobs                                                                               = parallelism.toInt
    require(k > 0 && jobs > 0, "k and jobs must be positive")
    val tasks = if (taskNames == "all") Tasks.all.map(_.id) else selection(taskNames)
    tasks.foreach(id => require(Tasks.byId(id).isDefined, s"Unknown agent task $id"))
    val cells = selection(cellNames).map(cell)
    val runs  = for {
      rep  <- 1 to k
      task <- tasks
      c    <- cells
    } yield Run(task, c, rep)
    def missing = runs.filterNot(run => Files.exists(dir.resolve(s"${run.name}.json")))
    List("pending", "errors", "logs").foreach(name => Files.createDirectories(dir.resolve(name)))

    def evaluate(flags: String*): Int = execute(
      Seq(
        Path.of(sys.props("java.home"), "bin", "java").toString,
        "-Dsun.java2d.opengl=false",
        "-cp",
        classpath,
        "it.unibo.llm.mcp.server.Evaluate",
        dir.toString
      ) ++ flags,
      ProcessBuilder.Redirect.appendTo(dir.resolve("logs/evaluate.log").toFile)
    )

    Using.resource(
      FileChannel.open(dir.resolve("pending/matrix.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    ) { file =>
      require(file.tryLock() != null, s"Another matrix is using $dir")
      val cleanup = sys.addShutdownHook {
        ProcessHandle.current().descendants().forEach(_.destroyForcibly())
      }
      try {
        if (missing.nonEmpty) {
          val pool      = Executors.newFixedThreadPool(jobs)
          val evaluator = Executors.newSingleThreadScheduledExecutor()
          try {
            evaluator.scheduleWithFixedDelay(
              () => if (evaluate("--threads=4") != 0) System.err.println("!! Evaluate failed, see logs/evaluate.log"),
              0,
              30,
              TimeUnit.SECONDS
            )
            passes(() => missing) { (pass, todo) =>
              println(s"-- pass $pass: ${todo.size} runs")
              pool
                .invokeAll(todo.map(run => (() => runOne(agent, dir, run)): Callable[Unit]).asJava)
                .asScala
                .foreach(_.get())
            }
          } finally {
            pool.shutdownNow()
            evaluator.shutdownNow()
            evaluator.awaitTermination(Long.MaxValue, TimeUnit.NANOSECONDS)
          }
        }
        require(evaluate() == 0, s"Final evaluation failed, see ${dir.resolve("logs/evaluate.log")}")
        val summary = Summary.write(dir)
        require(missing.isEmpty, s"${missing.size} runs still missing after three passes. Rerun the matrix to resume.")
        Report.write(dir, directory, k, jobs, tasks, cells.map(_.name), summary)
      } finally cleanup.remove()
    }
  }

}
