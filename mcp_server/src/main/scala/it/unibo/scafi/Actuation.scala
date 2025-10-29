package it.unibo.scafi

sealed trait Actuation
object Actuation {
  case class Rotation(rotationVector: (Double, Double)) extends Actuation
  case class Forward(vector: (Double, Double)) extends Actuation
  case object NoOp extends Actuation
  case object Stop extends Actuation
}
