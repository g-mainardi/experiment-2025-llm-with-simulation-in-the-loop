package it.unibo.llm.server.utils

import com.github.tototoshi.csv.{CSVReader, DefaultCSVFormat}
import it.unibo.alchemist.boundary.LoadAlchemist
import it.unibo.alchemist.model.terminators.AfterTime
import it.unibo.alchemist.model.times.DoubleTime

import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.Base64
import scala.concurrent.duration.Duration
import scala.io.Source
import scala.jdk.OptionConverters.RichOptional
import scala.reflect.internal.util.BatchSourceFile
import scala.tools.nsc.reporters.StoreReporter
import scala.tools.nsc.{Global, Settings}

object ScafiTestUtils {

  private val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  def compileAndGetErrors(code: String): (Boolean, List[String]) = {
    val tempDir = Files.createTempDirectory("scafi-compile-")
    val settings = new Settings()
    settings.usejavacp.value = true
    settings.outputDirs.setSingleOutput(tempDir.toFile.toString)

    val sourceFile = new BatchSourceFile("test.scala", code)
    compileSources(settings, List(sourceFile))
  }

  def simulateProgram(program: String, timeout: Duration): (Boolean, List[String], Option[String]) = {
    val tempDir = Files.createTempDirectory("scafi-simulate-")

    val packageRegex = """(?m)^\s*package\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)""".r
    val classNameRegex = """(?m)^\s*(?:.*?\s)?(?:case\s+)?class\s+([A-Za-z_]\w*)\b""".r

    val packageIfAny = packageRegex.findFirstMatchIn(program).map(_.group(1)).getOrElse("")
    val className = classNameRegex.findFirstMatchIn(program).map(_.group(1)).getOrElse(
      return (true, List("Simulation error: Could not find class definition in the provided code:\n\n" + program), None)
    )
    val fqnClass = if (packageIfAny.nonEmpty) s"$packageIfAny.$className" else className

    val pathWithPackage = if (packageIfAny.nonEmpty) {
      packageIfAny.replace(".", File.separator) + s"${File.separator}$className.scala"
    } else s"$className.scala"

    val sourceFile = {
      val dest = tempDir.resolve(pathWithPackage)
      Files.createDirectories(dest.getParent)
      dest
    }
    Files.writeString(sourceFile, program)

    val settings = new Settings()
    settings.classpath.value = System.getProperty("java.class.path")
    settings.outputDirs.setSingleOutput(tempDir.toFile.toString)

    val batch = new BatchSourceFile(sourceFile.toString, program)
    val (hasErrors, errors) = compileSources(settings, List(batch))
    if (hasErrors) return (true, errors, None)

    val urls = Array(tempDir.toUri.toURL)
    val parent = Thread.currentThread.getContextClassLoader
    val loader = new URLClassLoader(urls, parent)
    Thread.currentThread.setContextClassLoader(loader)

    try {
      Class.forName(fqnClass, true, loader)

      val exportDir = tempDir.resolve("export")
      val simulationSpec = Source.fromResource("swarmSimulation.yml").mkString
        .replace("{{ }}", fqnClass)
        .replace("{{EXPORT_DIR}}", exportDir.toAbsolutePath.toString)
      val simulationFile = Files.createTempFile(tempDir, "swarmSimulation", ".yml")

      Files.writeString(simulationFile, simulationSpec)

      val alchemistLoader = LoadAlchemist.from(simulationFile.toFile)
      val simulation = alchemistLoader.getDefault
      simulation.getEnvironment.addTerminator(new AfterTime(new DoubleTime(3000.0)))
      simulation.play()
      simulation.run()

      val positions = loadCsvFromSimulation(exportDir)
      logger.info(s"Simulation completed. Loaded positions for ${positions.size} timestamps.")
      logger.debug(s"Positions data: $positions")
      val pngBase64 = plotNodePositions(positions, exportDir)

      val containErrors = simulation.getError.isPresent
      (containErrors, simulation.getError.toScala.toList.map(e => s"Simulation error: ${e.getMessage}"), Some(pngBase64))
    } finally {
      Thread.currentThread.setContextClassLoader(parent)
      loader.close()
    }
  }

  implicit object AlchemistFormat extends DefaultCSVFormat {
    override val delimiter: Char = ' '
  }

  private def loadCsvFromSimulation(exportDir: Path): NodePosition.TimeStampedNodesPositions = {
    val csvFile = exportDir.resolve("experiment.csv")
    val sanitizedContent = Files.readString(csvFile)
      .linesIterator
      .filterNot(_.startsWith("#"))
      .mkString("\n")
    val sanitizedFile = Files.writeString(csvFile, sanitizedContent)
    val reader = CSVReader.open(sanitizedFile.toFile)
    reader.iterator.map { row =>
      val time = row.head.toDouble
      val position = row.tail.grouped(2).zipWithIndex.map {
        case (List(x, y), index) => index.toString -> NodePosition(x.toDouble, y.toDouble)
      }.toMap
      time -> position
    }.toMap
  }

  private def plotNodePositions(positions: NodePosition.TimeStampedNodesPositions, outputFile: Path): String = {
    import org.nspl._
    import org.nspl.awtrenderer._
    val plots = positions.toSeq.sortBy(_._1).map {
      case (time, nodePosition) =>
        val data = nodePosition.map { case (_, position) => (position.x, position.y) }.toSeq
        val minX = data.map(_._1).min
        val maxX = data.map(_._1).max
        val minY = data.map(_._2).min
        val maxY = data.map(_._2).max
        xyplot(data)(par
          .withMain("Node Positions at time " + time)
          .xlim(Some((minX - 0.5d) -> (maxX + 0.5d)))
          .ylim(Some((minY - 0.5d) -> (maxY + 0.5d)))
        )
    }
    val plot = sequence(plots, TableLayout(3))
    val plotFile = outputFile.resolve("node_positions.png").toFile
    pngToFile(plotFile, plot.build, width = 1000)
    val byteArray = renderToByteArray(plot.build, width = 1000)
    Base64.getEncoder.encodeToString(byteArray)
  }

  private def compileSources(settings: Settings, sources: List[BatchSourceFile]): (Boolean, List[String]) = {
    val reporter = new StoreReporter(settings)
    val compiler = new Global(settings, reporter)
    val run = new compiler.Run()

    try {
      run.compileSources(sources)
      val errors = reporter.infos.map(info =>
        s"${info.severity}: ${info.msg} at line ${info.pos.line}"
      ).toList
      (reporter.hasErrors, errors)
    } catch {
      case e: Exception => (true, List(s"Compilation error: ${e.getMessage}"))
    }
  }
}
