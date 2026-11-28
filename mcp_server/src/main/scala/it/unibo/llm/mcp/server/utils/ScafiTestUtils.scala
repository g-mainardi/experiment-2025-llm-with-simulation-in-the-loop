package it.unibo.llm.mcp.server.utils

import com.github.tototoshi.csv.CSVReader
import com.github.tototoshi.csv.DefaultCSVFormat
import dotty.tools.dotc.Driver
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interfaces.{Diagnostic => Level}
import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.dotc.reporting.HideNonSensicalMessages
import dotty.tools.dotc.reporting.Reporter
import dotty.tools.dotc.reporting.UniqueMessagePositions
import it.unibo.alchemist.boundary.LoadAlchemist
import it.unibo.alchemist.boundary.Loader
import it.unibo.alchemist.boundary.Variable
import it.unibo.alchemist.boundary.modelproviders.YamlProvider
import it.unibo.alchemist.boundary.variables.LinearVariable
import org.kaikikm.threadresloader.ResourceLoader

import java.io.StringReader
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters.RichOptional
import scala.jdk.StreamConverters._

object ScafiTestUtils {

  private val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  // ResourceLoader delegates to the server classpath first, so classes with the same name already compiled into the
  // server (e.g. it.unibo.scafi.CircleFormation) would win: each simulation uses a new package.
  private val runCounter = new AtomicLong()

  // Stops simulations that outlive their timeout
  private val timer = Executors.newSingleThreadScheduledExecutor { task =>
    val thread = new Thread(task, "simulation-timeout")
    thread.setDaemon(true)
    thread
  }

  // One compilation at a time: each one loads the whole server classpath, so twenty in parallel (Evaluate runs
  // simulations on every core) took more than 3 GB and got the JVM killed. A compilation takes about 0.3 s, a
  // simulation several seconds: the simulations still run in parallel.
  private val compilerLock = new Object

  /** Scenario variables the tool accepts as parameters, read from the YAML. */
  lazy val variables: Map[String, Variable[?]] =
    LoadAlchemist.from(getClass.getResource("/swarmSimulation.yml")).getVariables.asScala.toMap

  /** One line per variable: name, default and allowed values. */
  lazy val variablesDescription: String = variables.toList
    .sortBy(_._1)
    .map { case (name, variable) =>
      val allowed = variable match {
        case linear: LinearVariable => s"from ${linear.getMin} to ${linear.getMax} in steps of ${linear.getStep}"
        case other                  => s"one of ${values(other).mkString(", ")}"
      }
      s"- $name: default ${variable.getDefault}, $allowed"
    }
    .mkString("\n")

  private def values(variable: Variable[?]): List[Double] = variable.stream.toScala(List).map(_.toString.toDouble)

  private def isAllowed(variable: Variable[?], value: Double): Boolean = variable match {
    case linear: LinearVariable =>
      val steps = (value - linear.getMin) / linear.getStep
      value >= linear.getMin && value <= linear.getMax && math.abs(steps - steps.round) < 1e-9
    case other => values(other).contains(value)
  }

  /** The scenario with the program class and the export dir filled in, and YAML fragments merged on top. */
  def loadScenario(programClass: String, exportDir: Path, overrides: List[String] = Nil): Loader = {
    val spec = Source
      .fromResource("swarmSimulation.yml")
      .mkString
      .replace("{{ }}", programClass)
      .replace("{{EXPORT_DIR}}", exportDir.toAbsolutePath.toString)
    LoadAlchemist.from(new StringReader(spec), YamlProvider.INSTANCE, overrides.asJava)
  }

  def compileAndGetErrors(code: String): (Boolean, List[String]) =
    compile(code, Files.createTempDirectory("scafi-compile-"))

  /** A finished simulation: node positions at each export, and notes such as a simulation error or the timeout. */
  final case class Trace(
      positions: NodePosition.TimeStampedNodesPositions,
      failed: Boolean,
      notes: List[String],
      exportDir: Path
  )

  /** What `simulate_code` returns: whether it failed, the messages for the agent and the plot as base64 PNG. */
  def simulateProgram(
      program: String,
      timeout: Duration,
      parameters: Map[String, Any] = Map.empty
  ): (Boolean, List[String], Option[String]) =
    simulate(program, timeout, parameters) match {
      case Left(errors) => (true, errors, None)
      case Right(trace) => (trace.failed, trace.notes, Some(plotNodePositions(trace.positions, trace.exportDir)))
    }

  /** Compiles `program` in a package of its own and simulates it with the scenario `parameters` and the YAML fragments
    * `yaml` (not validated, e.g. a longer run or a denser export). Left holds the errors that prevented the simulation:
    * invalid parameters, no class, compilation errors.
    */
  def simulate(
      program: String,
      timeout: Duration,
      parameters: Map[String, Any] = Map.empty,
      yaml: List[String] = Nil
  ): Either[List[String], Trace] = {
    // getWith only rejects unknown names: out of range values would silently change the scenario
    val invalid = parameters.filter {
      case (name, value: Number) => !variables.get(name).exists(isAllowed(_, value.doubleValue))
      case _                     => true
    }
    val overrides = parameters.collect { case (name, value: Number) => name -> value.doubleValue }
    // Same offsets as program, so the package matches still point at the right place in it
    val blanked = blankCommentsAndStrings(program)
    for {
      _ <- Either.cond(
             invalid.isEmpty,
             (),
             List(s"Invalid parameters: ${invalid.mkString(", ")}. Scenario variables:\n$variablesDescription")
           )
      className <-
        mainClassName(blanked)
          .toRight(List("Simulation error: Could not find class definition in the provided code:\n\n" + program))
      tempDir   = Files.createTempDirectory("scafi-simulate-")
      fqnClass <- compileInOwnPackage(program, blanked, className, tempDir)
    } yield run(fqnClass, tempDir, timeout, overrides, yaml)
  }

  private val packageRegex = """(?m)^\s*package\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)""".r

  /** Compiles `program` (`blanked` is the same with comments and literals blanked out) into `outDir`, in a package of
    * its own. Right holds the fully qualified name of `className`, Left the compilation errors.
    */
  private def compileInOwnPackage(
      program: String,
      blanked: String,
      className: String,
      outDir: Path
  ): Either[List[String], String] = {
    val packages = packageRegex.findAllMatchIn(blanked).toList
    // Chained package clause: classes of the outer packages (e.g. ShapeFormation) stay visible.
    // It goes on the same line as the last clause, so error line numbers still match the submitted program.
    val runPackage    = s"run${runCounter.incrementAndGet()}"
    val uniqueProgram = packages.lastOption match {
      case Some(last) => program.patch(last.end, s"; package $runPackage", 0)
      case None       => s"package $runPackage; $program"
    }
    compile(uniqueProgram, outDir) match {
      case (true, errors) => Left(errors)
      case _              => Right((packages.map(_.group(1)) :+ runPackage :+ className).mkString("."))
    }
  }

  /** Simulates `fqnClass`, compiled into `classDir`, with the scenario `overrides` and the YAML fragments `yaml`. */
  private def run(
      fqnClass: String,
      classDir: Path,
      timeout: Duration,
      overrides: Map[String, Double],
      yaml: List[String]
  ): Trace = {
    // RunScafiProgram loads the program through ResourceLoader (one classloader per thread), not through the
    // context classloader. A fresh loader per simulation, closed at the end: classes of earlier simulations
    // don't stay reachable and memory doesn't grow with every call.
    ResourceLoader.setURLs(classDir.toUri.toURL)
    try {
      ResourceLoader.classForName(fqnClass)
      logger.info(s"Simulating $fqnClass")

      val exportDir  = classDir.resolve("export")
      val simulation = loadScenario(fqnClass, exportDir, yaml).getWith(overrides.asJava)
      // run() stays on this thread: RunScafiProgram resolves the program through this thread's ResourceLoader
      val timedOut = new AtomicBoolean()
      val stopper  = timer.schedule(
        () => { timedOut.set(true); simulation.terminate() },
        timeout.toMillis,
        TimeUnit.MILLISECONDS
      )
      try {
        simulation.play()
        simulation.run()
      } finally stopper.cancel(false)

      val positions = loadCsvFromSimulation(exportDir)
      logger.info(s"Simulation completed. Loaded positions for ${positions.size} timestamps.")
      logger.debug(s"Positions data: $positions")

      val errors  = Option(simulation.getError.getOrNull).toList.map(e => s"Simulation error: ${e.getMessage}")
      val stopped = Option.when(timedOut.get)(
        s"Simulation stopped by the ${timeout.toSeconds} s timeout at time ${simulation.getTime.getCurrent}: " +
          "the plot shows the run up to there"
      )
      Trace(positions, simulation.getError.isSome, errors ++ stopped, exportDir)
    } finally {
      ResourceLoader.getClassLoader.asInstanceOf[URLClassLoader].close()
      ResourceLoader.setDefault()
    }
  }

  private val classRegex = """\bclass\s+([A-Za-z_]\w*)""".r

  private val abstractRegex = """\babstract\b""".r

  private val formationRegex = """\bextends\s+(?:[\w.]*\.)?ShapeFormation\b""".r

  private val charLiteralRegex = """'(?:\\u[0-9a-fA-F]{4}|\\.|[^\\'\n])'""".r

  /** The class to simulate, from source with comments and literals blanked out: among the concrete classes, the first
    * one that extends `ShapeFormation`, else the last one. Models write helper classes, redefine `ShapeFormation`
    * before the formation and mention classes in comments.
    */
  private def mainClassName(code: String): Option[String] = {
    val matches  = classRegex.findAllMatchIn(code).toList
    val concrete = matches.zip(matches.drop(1).map(_.start) :+ code.length).collect {
      case (m, next)
          if abstractRegex.findFirstIn(code.substring(code.lastIndexOf('\n', m.start) + 1, m.start)).isEmpty =>
        m.group(1) -> code.substring(m.end, next).takeWhile(_ != '{')
    }
    concrete.collectFirst { case (name, header) if formationRegex.findFirstIn(header).nonEmpty => name }
      .orElse(concrete.lastOption.map(_._1))
  }

  /** `code` with comments, string and char literals turned into spaces, newlines kept: same length and offsets. */
  private def blankCommentsAndStrings(code: String): String = {
    def blanked(from: Int, to: Int): String =
      code.substring(from, to).map(c => if (c == '\n') '\n' else ' ')

    @tailrec
    def blockCommentEnd(j: Int, depth: Int): Int =
      if (j >= code.length || depth == 0) j
      else
        code.slice(j, j + 2) match {
          case "/*" => blockCommentEnd(j + 2, depth + 1)
          case "*/" => blockCommentEnd(j + 2, depth - 1)
          case _    => blockCommentEnd(j + 1, depth)
        }

    @tailrec
    def stringEnd(j: Int): Int =
      if (j >= code.length) code.length
      else
        code(j) match {
          case '"'  => j + 1
          case '\n' => j
          case '\\' => if (j + 1 < code.length && code(j + 1) != '\n') stringEnd(j + 2) else j + 1
          case _    => stringEnd(j + 1)
        }

    @tailrec
    def extraQuotesEnd(j: Int): Int =
      if (j < code.length && code(j) == '"') extraQuotesEnd(j + 1) else j

    def literalOrCommentEnd(i: Int): Option[Int] = code(i) match {
      case '/' if code.startsWith("//", i) => Some(Some(code.indexOf('\n', i)).filter(_ >= 0).getOrElse(code.length))
      // Scala block comments nest
      case '/' if code.startsWith("/*", i)     => Some(blockCommentEnd(i + 2, 1))
      case '"' if code.startsWith("\"\"\"", i) =>
        Some(Some(code.indexOf("\"\"\"", i + 3)).filter(_ >= 0).fold(code.length)(k => extraQuotesEnd(k + 3)))
      case '"'  => Some(stringEnd(i + 1))
      case '\'' => charLiteralRegex.findPrefixMatchOf(code.substring(i, (i + 8).min(code.length))).map(i + _.end)
      case _    => None
    }

    @tailrec
    def scan(i: Int, plainFrom: Int, parts: List[String]): String =
      if (i >= code.length)
        (code.substring(plainFrom, code.length) :: parts).reverse.mkString
      else
        literalOrCommentEnd(i) match {
          case Some(end) =>
            scan(end, end, blanked(i, end) :: code.substring(plainFrom, i) :: parts)
          case None =>
            scan(i + 1, plainFrom, parts)
        }

    scan(0, 0, Nil)
  }

  implicit object AlchemistFormat extends DefaultCSVFormat {

    override val delimiter: Char = ' '

  }

  private def loadCsvFromSimulation(exportDir: Path): NodePosition.TimeStampedNodesPositions = {
    // The exporter appends the variable values to the file name: the export dir holds just this file
    val csvFile          = Files.list(exportDir).toScala(List).find(_.toString.endsWith(".csv")).get
    val sanitizedContent = Files
      .readString(csvFile)
      .linesIterator
      .filterNot(_.startsWith("#"))
      .mkString("\n")
    val sanitizedFile = Files.writeString(csvFile, sanitizedContent)
    val reader        = CSVReader.open(sanitizedFile.toFile)
    reader.iterator.map { row =>
      val time     = row.head.toDouble
      val position = row.tail
        .grouped(2)
        .zipWithIndex
        .map { case (xy, index) =>
          index.toString -> NodePosition(xy(0).toDouble, xy(1).toDouble)
        }
        .toMap
      time -> position
    }.toMap
  }

  private def plotNodePositions(positions: NodePosition.TimeStampedNodesPositions, outputFile: Path): String = {
    import org.nspl._
    import org.nspl.awtrenderer._
    val plots = positions.toSeq.sortBy(_._1).map { case (time, nodePosition) =>
      val data = nodePosition.values.map(position => (position.x, position.y)).toSeq
      val minX = data.map(_._1).min
      val maxX = data.map(_._1).max
      val minY = data.map(_._2).min
      val maxY = data.map(_._2).max
      xyplot(data)(
        par
          .withMain("Node Positions at time " + time)
          .xlim(Some((minX - 0.5d) -> (maxX + 0.5d)))
          .ylim(Some((minY - 0.5d) -> (maxY + 0.5d)))
      )
    }
    val plot     = sequence(plots, TableLayout(math.ceil(math.sqrt(plots.size)).toInt))
    val plotFile = outputFile.resolve("node_positions.png").toFile
    pngToFile(plotFile, plot.build, width = 1000)
    val byteArray = renderToByteArray(plot.build, width = 1000)
    Base64.getEncoder.encodeToString(byteArray)
  }

  /** Compiles `code` with the Scala 3 compiler, against the server classpath, into `outDir`. */
  def compile(code: String, outDir: Path): (Boolean, List[String]) = {
    val source   = Files.writeString(outDir.resolve("Program.scala"), code)
    val messages = ListBuffer.empty[String]
    // Same mixins as the reporter dotc builds for a SimpleReporter, without the "N errors found" summary
    val reporter = new Reporter with UniqueMessagePositions with HideNonSensicalMessages {
      override def doReport(d: Diagnostic)(using Context): Unit = {
        val severity = d.level match {
          case Level.ERROR   => "ERROR"
          case Level.WARNING => "WARNING"
          case _             => "INFO"
        }
        // dotc lines are 0-based: the agent reads 1-based lines, as nsc reported them
        val line = d.position.toScala.fold("")(p => s" at line ${p.line + 1}")
        messages += s"$severity: ${d.message}$line"
      }

      override def printSummary()(using Context): Unit = ()
    }
    try {
      val args = Array("-usejavacp", "-d", outDir.toString, source.toString)
      (compilerLock.synchronized(new Driver().process(args, reporter).hasErrors), messages.toList)
    } catch {
      case e: Exception => (true, List(s"Compilation error: ${e.getMessage}"))
    }
  }

}
