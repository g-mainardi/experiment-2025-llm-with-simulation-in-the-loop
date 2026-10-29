package it.unibo.scafi

import it.unibo.alchemist.model.scafi.ScafiIncarnationForAlchemist.AggregateProgram
import it.unibo.alchemist.model.scafi.ScafiIncarnationForAlchemist.BlockC
import it.unibo.alchemist.model.scafi.ScafiIncarnationForAlchemist.BlockG
import it.unibo.alchemist.model.scafi.ScafiIncarnationForAlchemist.ScafiAlchemistSupport
import it.unibo.alchemist.model.scafi.ScafiIncarnationForAlchemist.StandardSensors

trait BaseFormation extends AggregateProgram with StandardSensors with BlockG with BlockC with ScafiAlchemistSupport {

  def distanceVector: (Double, Double) = {
    val result = nbrVector()
    (result._1, result._2)
  }

  def module(position: (Double, Double)): Double =
    Math.sqrt(position._1 * position._1 + position._2 * position._2)

  def normalize(position: (Double, Double)): (Double, Double) = {
    val module = this.module(position)
    (position._1 / module, position._2 / module)
  }

  def rotate90(position: (Double, Double)): (Double, Double) =
    (-position._2, position._1)

  /** The `name` molecule if the scenario defines it, otherwise `default`. */
  def param(name: String, default: Double): Double = node.getOrElse(name, default)

}
