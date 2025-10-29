package it.unibo.llm

import it.unibo.alchemist.boundary.LoadAlchemist

object Main {
  def main(args: Array[String]): Unit = {
    val simulation = LoadAlchemist.from(getClass.getResource("/swarmSimulationTest.yml")).getDefault
    //simulation.getEnvironment.addTerminator(new AfterTime(new DoubleTime(100.0)))
    simulation.play()
    simulation.run()
    simulation.getError.ifPresent(e => throw e)
  }
}