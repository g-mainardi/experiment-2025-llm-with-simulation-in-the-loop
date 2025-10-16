package it.unibo.llm.mcp.server.utils

import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.reporters.StoreReporter
import scala.reflect.internal.util.BatchSourceFile

object ScafiTestUtils {
  def compileAndGetErrors(code: String): (Boolean, List[String]) = {
    val settings = new Settings()
    settings.usejavacp.value = true

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
      case e: Exception => (false, List(s"Compilation error: ${e.getMessage}"))
    }
  }
}
