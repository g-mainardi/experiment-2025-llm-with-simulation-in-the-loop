package it.unibo.alchemist.action

import it.unibo.alchemist.model.{Node, NodeProperty}

object DirectionalRobotProperty {
  val DefaultWheelBase: Double = 0.5 // meters
  val ZeroVelocity: Double = 0.0
}

class DirectionalRobotProperty[T](
  node: Node[T], 
  initialAngle: Double = 0.0,
  val wheelBase: Double = DirectionalRobotProperty.DefaultWheelBase
) extends NodeProperty[T] {
  import DirectionalRobotProperty._
  private val TwoWheels = 2.0
  private var leftWheelVelocity: Double = ZeroVelocity
  private var rightWheelVelocity: Double = ZeroVelocity
  private var orientationX: Double = math.cos(initialAngle)
  private var orientationY: Double = math.sin(initialAngle)

  override def getNode: Node[T] = node

  override def cloneOnNewNode(node: Node[T]): NodeProperty[T] = 
    new DirectionalRobotProperty[T](node, getAngle, wheelBase)

  def orientation: (Double, Double) = (orientationX, orientationY)
  
  def getAngle: Double = math.atan2(orientationY, orientationX)
  
  private def normalizeOrientation(): Unit = {
    val magnitude = math.sqrt(orientationX * orientationX + orientationY * orientationY)
    if (magnitude > 0) {
      orientationX = orientationX / magnitude
      orientationY = orientationY / magnitude
    }
  }

  def spinBy(angle: Double): Unit = {
    val currentAngle = getAngle
    val newAngle = currentAngle + angle
    orientationX = math.cos(newAngle)
    orientationY = math.sin(newAngle)
    normalizeOrientation()
  }

  def setWheelVelocities(left: Double, right: Double): Unit = {
    leftWheelVelocity = left
    rightWheelVelocity = right
  }

  def getLeftWheelVelocity: Double = leftWheelVelocity

  def getRightWheelVelocity: Double = rightWheelVelocity

  def getLinearVelocity: Double = (leftWheelVelocity + rightWheelVelocity) / TwoWheels

  def getAngularVelocity: Double = (rightWheelVelocity - leftWheelVelocity) / wheelBase

  def updateOrientation(deltaTime: Double): Unit = {
    val angularVelocity = getAngularVelocity
    spinBy(angularVelocity * deltaTime)
  }

  def predictOrientation(deltaTime: Double): (Double, Double) = {
    val angularVelocity = getAngularVelocity
    val currentAngle = getAngle
    val newAngle = currentAngle + angularVelocity * deltaTime
    (math.cos(newAngle), math.sin(newAngle))
  }
}
