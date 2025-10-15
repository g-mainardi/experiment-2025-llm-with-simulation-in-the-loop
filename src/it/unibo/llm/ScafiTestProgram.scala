package it.unibo.llm

import it.unibo.alchemist.model.scafi.ScafiIncarnationForAlchemist._

class ScafiTestProgram extends AggregateProgram with StandardSensors with ScafiAlchemistSupport with BlockG with BlockC with BlockS {
  private lazy val temp = randomGenerator().nextDouble() * 100
  override def main(): Any = {
  }
}
