package it.unibo.llm

import it.unibo.alchemist.boundary.LoadAlchemist
import it.unibo.alchemist.boundary.swingui.monitor.impl.SwingGUI
import it.unibo.alchemist.model.{Environment, Position, Position2D}
import it.unibo.alchemist.model.terminators.AfterTime
import it.unibo.alchemist.model.times.DoubleTime

object Main extends App {
  val simulation = LoadAlchemist.from(getClass.getResource("/swarmSimulation.yml")).getDefault
  //simulation.getEnvironment.addTerminator(new AfterTime(new DoubleTime(100.0)))
  simulation.play()
  simulation.run()
  simulation.getError.ifPresent(e => throw e)
}