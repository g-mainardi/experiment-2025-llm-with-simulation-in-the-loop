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
import org.kaikikm.threadresloader.ResourceLoader

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.io.Source
import scala.jdk.OptionConverters.RichOptional
import scala.jdk.StreamConverters._

object ScafiTestUtils {

  private val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  // ResourceLoader delegates to the server classpath first, so classes with the same name already compiled into the
  // server (e.g. it.unibo.scafi.CircleFormation) would win: each simulation uses a new package.
  private val runCounter = new AtomicLong()

  def compileAndGetErrors(code: String): (Boolean, List[String]) =
    compile(code, Files.createTempDirectory("scafi-compile-"))

  def simulateProgram(program: String, timeout: Duration): (Boolean, List[String], Option[String]) = {
    val tempDir = Files.createTempDirectory("scafi-simulate-")

    val packageRegex   = """(?m)^\s*package\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)""".r
    val classNameRegex = """(?m)^\s*(?:.*?\s)?(?:case\s+)?class\s+([A-Za-z_]\w*)\b""".r

    val packages  = packageRegex.findAllMatchIn(program).toList
    val className = classNameRegex.findFirstMatchIn(program) match {
      case Some(m) => m.group(1)
      case None    =>
        return (
          true,
          List("Simulation error: Could not find class definition in the provided code:\n\n" + program),
          None
        )
    }
    // Chained package clause: classes of the outer packages (e.g. ShapeFormation) stay visible.
    // It goes on the same line as the last clause, so error line numbers still match the submitted program.
    val runPackage    = s"run${runCounter.incrementAndGet()}"
    val uniqueProgram = packages.lastOption match {
      case Some(last) => program.patch(last.end, s"; package $runPackage", 0)
      case None       => s"package $runPackage; $program"
    }
    val fqnClass = (packages.map(_.group(1)) :+ runPackage :+ className).mkString(".")

    val (hasErrors, errors) = compile(uniqueProgram, tempDir)
    if (hasErrors) return (true, errors, None)

    // RunScafiProgram loads the program through ResourceLoader (one classloader per thread), not through the
    // context classloader. A fresh loader per simulation, closed at the end: classes of earlier simulations
    // don't stay reachable and memory doesn't grow with every call.
    ResourceLoader.setURLs(tempDir.toUri.toURL)
    try {
      ResourceLoader.classForName(fqnClass)
      logger.info(s"Simulating $fqnClass")

      val exportDir      = tempDir.resolve("export")
      val simulationSpec = Source
        .fromResource("swarmSimulation.yml")
        .mkString
        .replace("{{ }}", fqnClass)
        .replace("{{EXPORT_DIR}}", exportDir.toAbsolutePath.toString)
      val simulationFile = Files.createTempFile(tempDir, "swarmSimulation", ".yml")

      Files.writeString(simulationFile, simulationSpec)

      val alchemistLoader = LoadAlchemist.from(simulationFile.toFile)
      val simulation      = alchemistLoader.getDefault
      simulation.play()
      simulation.run()

      val positions = loadCsvFromSimulation(exportDir)
      logger.info(s"Simulation completed. Loaded positions for ${positions.size} timestamps.")
      logger.debug(s"Positions data: $positions")
      val pngBase64 = plotNodePositions(positions, exportDir)

      val containErrors = simulation.getError.isSome
      val errors        = Option(simulation.getError.getOrNull).toList.map(e => s"Simulation error: ${e.getMessage}")
      (containErrors, errors, Some(pngBase64))
    } finally {
      ResourceLoader.getClassLoader.asInstanceOf[URLClassLoader].close()
      ResourceLoader.setDefault()
    }
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
      val data = nodePosition.map { case (_, position) => (position.x, position.y) }.toSeq
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
    val plot     = sequence(plots, TableLayout(3))
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
      (new Driver().process(args, reporter).hasErrors, messages.toList)
    } catch {
      case e: Exception => (true, List(s"Compilation error: ${e.getMessage}"))
    }
  }

}
