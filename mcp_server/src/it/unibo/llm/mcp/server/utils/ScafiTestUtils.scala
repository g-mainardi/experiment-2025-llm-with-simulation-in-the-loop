package it.unibo.llm.mcp.server.utils

import java.nio.file.Files
import scala.concurrent.duration.Duration
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
    ???
  }
}
