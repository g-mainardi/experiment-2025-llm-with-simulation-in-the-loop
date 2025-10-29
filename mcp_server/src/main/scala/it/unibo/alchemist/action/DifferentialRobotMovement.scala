package it.unibo.alchemist.action

import it.unibo.alchemist.model.actions.AbstractMoveNode
import it.unibo.alchemist.model.times.DoubleTime
import it.unibo.alchemist.model._
import it.unibo.scafi.Actuation
import it.unibo.scafi.Actuation.{Forward, NoOp, Rotation, Stop}

object DifferentialRobotMovement {
  val AlignmentTolerance: Double = 0.1
  val DefaultRotationSpeedFactor: Double = 0.1
  val ZeroVelocity: Double = 0.0
}

class DifferentialRobotMovement[T, P <: Position[P]](
  environment: Environment[T, P],
  node: Node[T],
  moleculeToObserve: Molecule,
  speed: Double,
  rotationSpeedFactor: Double = DifferentialRobotMovement.DefaultRotationSpeedFactor
) extends AbstractMoveNode[T, P](environment, node, true){
  import DifferentialRobotMovement._
  
  private var previousTime: Time = new DoubleTime(0)
  
  override def getNextPosition: P = {
    val robotProperty = node.asProperty[DirectionalRobotProperty[T]](classOf[DirectionalRobotProperty[T]])
    val direction = node.getConcentration(moleculeToObserve).asInstanceOf[Actuation]
    val delta = environment.getSimulation.getTime.minus(previousTime).toDouble
    val result = direction match {
      case NoOp => stopRobot(robotProperty)
      case Stop => stopRobot(robotProperty)
      case Forward((dx, dy)) => handleForwardMovement(robotProperty, dx, dy, delta)
      case Rotation((rx, ry)) => handleRotation(robotProperty, rx, ry, delta)
      case _ => stopRobot(robotProperty)
    }
    previousTime = environment.getSimulation.getTime
    result
  }

  private def stopRobot(robotProperty: DirectionalRobotProperty[T]): P = {
    robotProperty.setWheelVelocities(ZeroVelocity, ZeroVelocity)
    environment.getPosition(node)
  }

  private def handleForwardMovement(robotProperty: DirectionalRobotProperty[T], dx: Double, dy: Double, delta: Double): P = {
    val targetAngle = math.atan2(dy, dx)
    val currentOrientation = robotProperty.getAngle // Use getAngle instead of orientation
    val angleDifference = computeAngleDifference(targetAngle, currentOrientation)
    val magnitude = math.sqrt(dx * dx + dy * dy)
    if (math.abs(angleDifference) < AlignmentTolerance) {
      moveForwardAligned(robotProperty, magnitude, currentOrientation, delta)
    } else {
      rotateInPlace(robotProperty, angleDifference, delta)
    }

  }

  private def handleRotation(robotProperty: DirectionalRobotProperty[T], rx: Double, ry: Double, delta: Double): P = {
    val targetAngle = math.atan2(rx, ry)
    val currentAngle = robotProperty.getAngle
    val angleDifference = computeAngleDifference(targetAngle, currentAngle)
    if (math.abs(angleDifference) > AlignmentTolerance) {
      val rotationSpeed = speed * rotationSpeedFactor
      val turnDirection = math.signum(angleDifference)
      setDifferentialRotation(robotProperty, rotationSpeed, turnDirection)
      robotProperty.updateOrientation(delta)
    } else {
      robotProperty.setWheelVelocities(ZeroVelocity, ZeroVelocity)
    }
    environment.getPosition(node)
  }

  private def moveForwardAligned(robotProperty: DirectionalRobotProperty[T], magnitude: Double, currentOrientation: Double, delta: Double): P = {
    val linearSpeed = magnitude * speed
    robotProperty.setWheelVelocities(linearSpeed, linearSpeed)
    val vx = linearSpeed * math.cos(currentOrientation) * delta
    val vy = linearSpeed * math.sin(currentOrientation) * delta
    environment.getPosition(node).plus(Array(vx, vy))
  }

  private def rotateInPlace(robotProperty: DirectionalRobotProperty[T], angleDifference: Double, delta: Double): P = {
    val rotationSpeed = speed * rotationSpeedFactor
    val turnDirection = math.signum(angleDifference)
    setDifferentialRotation(robotProperty, rotationSpeed, turnDirection)
    robotProperty.updateOrientation(delta)
    environment.getPosition(node)
  }

  private def normalizeAngle(angle: Double): Double =
    math.atan2(math.sin(angle), math.cos(angle))

  private def setDifferentialRotation(robotProperty: DirectionalRobotProperty[T], rotationSpeed: Double, turnDirection: Double): Unit = {
    robotProperty.setWheelVelocities(
      -rotationSpeed * turnDirection,
      rotationSpeed * turnDirection
    )
  }

  private def computeAngleDifference(targetAngle: Double, currentOrientation: Double): Double =
    normalizeAngle(targetAngle - currentOrientation)

  override def cloneAction(node: Node[T], reaction: Reaction[T]): Action[T] = {
    new DifferentialRobotMovement(environment, node, moleculeToObserve, speed, rotationSpeedFactor)
  }
}
