package it.unibo.llm.mcp.server.utils

import it.unibo.alchemist.boundary.LoadAlchemist
import it.unibo.alchemist.model.terminators.AfterTime
import it.unibo.alchemist.model.times.DoubleTime
import it.unibo.llm.Main.simulation

import java.io.File
import java.nio.file.Files
import scala.concurrent.duration.Duration
import scala.io.Source
import scala.jdk.OptionConverters.RichOptional
import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.reporters.StoreReporter
import scala.reflect.internal.util.BatchSourceFile

object ScafiTestUtils {
  def compileAndGetErrors(code: String): (Boolean, List[String]) = {
    val tempDir = Files.createTempDirectory("scafi-compile-")
    val settings = new Settings()
    settings.usejavacp.value = true

    settings.outputDirs.setSingleOutput(tempDir.toFile.toString)

    val reporter = new StoreReporter(settings)
    val compiler = new Global(settings, reporter)

    val sourceFile = new BatchSourceFile("test.scala", code)
    val run = new compiler.Run()

    try {
      run.compileSources(List(sourceFile))
      val errors = reporter.infos.map(info =>
        s"${info.severity}: ${info.msg} at line ${info.pos.line}"
      ).toList
      (reporter.hasErrors, errors)
    } catch {
      case e: Exception => (true, List(s"Compilation error: ${e.getMessage}"))
    }
  }

  def simulateProgram(program: String, timeout: Duration): (Boolean, List[String]) = {
    val tempDir = Files.createTempDirectory("scafi-simulate-")

    val packageRegex = """(?m)^\s*package\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)""".r
    val classNameRegex = """(?m)^\s*(?:.*?\s)?(?:case\s+)?class\s+([A-Za-z_]\w*)\b""".r

    val packageIfAny = packageRegex.findFirstMatchIn(program) match {
      case Some(m) => m.group(1)
      case None => ""
    }
    val className = classNameRegex.findFirstMatchIn(program) match {
      case Some(m) => m.group(1)
      case None => return (true, List("Simulation error: Could not find class definition in the provided code:\n\n" + program))
    }

    val fqnClass = if (packageIfAny.nonEmpty) s"$packageIfAny.$className" else className

    val pathWithPackage = if (packageIfAny.nonEmpty) {
      packageIfAny.replace(".", File.separator) + s"${File.separator}$className.scala"
    } else {
      s"$className.scala"
    }
    val sourceFile = if (packageIfAny.nonEmpty) {
      val dest = tempDir.resolve(pathWithPackage)
      Files.createDirectories(dest.getParent)
      dest
    } else {
      tempDir.resolve(pathWithPackage)
    }
    Files.writeString(sourceFile, program)

    val simulationSpec = Source.fromResource("swarmSimulation.yml").mkString.replace("{{ }}", fqnClass)
    val simulationFile = Files.createTempFile(tempDir, "swarmSimulation", ".yml")
    Files.writeString(simulationFile, simulationSpec)

    val simulation = LoadAlchemist.from(simulationFile.toFile).getDefault
    simulation.getEnvironment.addTerminator(new AfterTime(new DoubleTime(100.0)))
    simulation.play()
    simulation.run()
    val containErrors = simulation.getError.isPresent
    (containErrors, simulation.getError.toScala.toList.map(e => s"Simulation error: ${e.getMessage}"))
  }
}
