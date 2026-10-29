package it.unibo.llm.mcp.server

import it.unibo.llm.mcp.server.utils.ScafiTestUtils

import java.nio.file.Files
import scala.jdk.CollectionConverters._

/** Runs the scenario with CircleFormation in the Alchemist GUI, slowed down, for debugging. */
object Main {

  private val Gui = """
                      |monitors:
                      |  - type: SwingGUI
                      |    parameters:
                      |      graphics: effects/simulation.json
                      |""".stripMargin

  def main(args: Array[String]): Unit = {
    val simulation = ScafiTestUtils
      .loadScenario("it.unibo.scafi.CircleFormation", Files.createTempDirectory("scafi-main-"), List(Gui))
      .getWith(Map("speed" -> 0.0001).asJava)
    simulation.play()
    simulation.run()
    Option(simulation.getError.getOrNull).foreach(e => throw e)
  }

}
