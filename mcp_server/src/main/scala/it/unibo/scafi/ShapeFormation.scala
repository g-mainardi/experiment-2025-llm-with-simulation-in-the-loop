package it.unibo.scafi

import it.unibo.alchemist.action.DirectionalRobotProperty
import it.unibo.scafi.Actuation.{Forward, NoOp, Rotation}
import it.unibo.scafi.space.Point3D
import it.unibo.scafi.space.optimization.RichPoint3D


sealed trait Actuation
object Actuation {
  case class Rotation(rotationVector: (Double, Double)) extends Actuation
  case class Forward(vector: (Double, Double)) extends Actuation
  case object NoOp extends Actuation
  case object Stop extends Actuation
}

abstract class ShapeFormation() extends BaseFormation {
  private val repulsionStrength = 0.6
  private val maxRepulsion = 2

  implicit class InternalRichPoint3D(p: Point3D) {
    def magnitude: Double = p.distance(Point3D.Zero)
    def normalize: Point3D = {
      val m = p.magnitude
      if (m < 1e-9) { Point3D.Zero } else { Point3D(p.x / m, p.y / m, 0) }
    }
  }

  override def main(): Actuation = align(this.getClass) {
      _ => logic()
    }

  def logic(): Actuation = {
    val leaderSelected = sense[Int]("leader")
    val stabilityThreshold = sense[Double]("stabilityThreshold")
    val collisionArea = sense[Double]("collisionArea")
    val leader = isLeader(leaderSelected)
    val directionTowardsLeader = computeDirectionTowardsLeader(leader)
    val property = alchemistEnvironment.getNodeByID(mid).asProperty[DirectionalRobotProperty[Any]](classOf[DirectionalRobotProperty[Any]])
    val leaderOrientation = broadcast(leader, property.getAngle)
    val localGoal = computeLocalGoal(leader, leaderSelected, directionTowardsLeader)
    val neighborMap = buildNeighborMap()
    val avoidance = computeAvoidanceVector(neighborMap, collisionArea)
    val result = determineActuation(leader, localGoal, avoidance, leaderOrientation, stabilityThreshold)
    node.put("actuation", result)
    node.put("direction", Array(property.orientation._1, property.orientation._2))
    node.put("mid", mid)

    result
  }

  private def computeLocalGoal(
    leader: Boolean,
    leaderSelected: Int,
    directionTowardsLeader: (Double, Double)
  ): (Double, Double) = {
    val potential = classicGradient(leader)
    val collectInfo = C[Double, Map[Int, (Double, Double)]](
      potential = potential,
      acc = _ ++ _,
      local = Map(mid() -> directionTowardsLeader),
      Null = Map.empty
    ).filter(_._1 != mid())
    
    val ordered = orderedNodes(collectInfo.toSet)
    val suggestion = branch(leaderSelected == mid())(calculateSuggestion(ordered))(Map.empty)
    broadcast(leader, suggestion).getOrElse(mid, (0.0, 0.0))
  }

  private def computeDirectionTowardsLeader(leader: Boolean): (Double, Double) = {
    G[(Double, Double)](
      source = leader,
      field = (0.0, 0.0),
      acc = { case (x, y) => (x + distanceVector._1, y + distanceVector._2) },
      metric = nbrRange
    )
  }

  def calculateSuggestion(ordered: List[(Int, (Double, Double))]): Map[Int, (Double, Double)]

  protected def orderedNodes(nodes: Set[(Int, (Double, Double))]): List[(Int, (Double, Double))] =
    nodes.filter(_._1 != mid()).toList.sortBy(_._1)

  private def computeAvoidanceVector(neighborMap: Map[Int, Point3D], collisionArea: Double): Point3D = {
    val repulsionSum = computeRepulsionSum(neighborMap, collisionArea)
    if (repulsionSum.magnitude > maxRepulsion) {
      repulsionSum.normalize * maxRepulsion
    } else {
      repulsionSum
    }
  }

  private def computeRepulsionSum(neighborMap: Map[Int, Point3D], collisionArea: Double): Point3D =
    neighborMap.values
      .map { p =>
        val d = p.magnitude
        if (d < 1e-9 || d >= collisionArea) {
           Point3D.Zero
        } else {
          val proximity = math.max(0.0, 1.0 - d / collisionArea) // 0..1
          val weight = repulsionStrength * proximity / (d * d) // stronger when closer
          (p.normalize * weight) * -1.0
        }
      }.foldLeft(Point3D.Zero)(_ + _)

  private def buildNeighborMap(): Map[Int, Point3D] = {
    foldhoodPlus[Map[Int, (Double, Double)]](Map.empty)((a, b) => a ++ b)(Map(nbr(mid) -> distanceVector))
      .map { case (id, nbrVector) => id -> Point3D(nbrVector._1, nbrVector._2, 0.0) }
  }

  private def determineActuation(
    leader: Boolean,
    localGoal: (Double, Double),
    avoidance: Point3D,
    leaderOrientation: Double,
    stabilityThreshold: Double
  ): Actuation = {
    val distanceTowardGoal = calculateDistance(localGoal)
    val orientationVector = convertOrientationToVector(leaderOrientation)
    if (distanceTowardGoal < stabilityThreshold) {
      handleStableState(leader, orientationVector, avoidance)
    } else {
      handleMovementState(localGoal, avoidance)
    }
  }

  private def handleStableState(leader: Boolean, orientationVector: (Double, Double), avoidance: Point3D): Actuation = {
    if (leader) {
      NoOp
    } else {
      computeGoalConsideringAvoidance(orientationVector, avoidance)
    }
  }

  private def handleMovementState(localGoal: (Double, Double), avoidance: Point3D): Actuation = {
    val resultingVector = (Point3D(localGoal._1, localGoal._2, 0) + avoidance).normalize
    Forward((resultingVector.x, resultingVector.y))
  }

  private def computeGoalConsideringAvoidance(leaderOrientation: (Double, Double), avoidance: Point3D): Actuation =
    if(avoidance.magnitude > 0.01) {
      val combinedVector = (Point3D(leaderOrientation._1, leaderOrientation._2, 0) + avoidance).normalize
      Forward((combinedVector.x, combinedVector.y))
    } else {
      Rotation(leaderOrientation._1, leaderOrientation._2)
    }

  // Utility methods
  private def isLeader(leaderSelected: Int): Boolean = 
    mid() == leaderSelected

  private def calculateDistance(goal: (Double, Double)): Double = 
    Math.sqrt(goal._1 * goal._1 + goal._2 * goal._2)

  private def convertOrientationToVector(orientation: Double): (Double, Double) = 
    (-math.sin(orientation), math.cos(orientation))
}

class LineFormation extends ShapeFormation() {
  override def calculateSuggestion(ordered: List[(Int, (Double, Double))]): Map[Int, (Double, Double)] = {
    val (leftSlots, rightSlots) = ordered.indices.splitAt(ordered.size / 2)
    var devicesAvailable = ordered
    val leftCandidates = leftSlots
      .map { index =>
        val candidate = devicesAvailable
          .map {
            case (id, (xPos, yPos)) =>
              val newPos @ (newXpos, newYpos) = ((-(index + 1) * distanceThreshold) + xPos, yPos)
              val modulo = math.sqrt((newXpos * newXpos) + (newYpos * newYpos))
              (id, modulo, newPos)
          }
          .minBy(_._2)
        devicesAvailable = devicesAvailable.filterNot(_._1 == candidate._1)
        candidate._1 -> candidate._3
      }
      .toMap
    val rightCandidates = rightSlots
      .map(i => i - rightSlots.min)
      .map { index =>
        val candidate = devicesAvailable
          .map {
            case (id, (xPos, yPos)) =>
              val newPos @ (newXpos, newYpos) = (((index + 1) * distanceThreshold) + xPos, yPos)
              val modulo = math.sqrt((newXpos * newXpos) + (newYpos * newYpos))
              (id, modulo, newPos)
          }
          .minBy(_._2)
        devicesAvailable = devicesAvailable.filterNot(_._1 == candidate._1)
        candidate._1 -> candidate._3
      }
      .toMap
    leftCandidates ++ rightCandidates
  }

  private def distanceThreshold: Double = {
    node.getOrElse(LineFormation.INTER_DISTANCE_SENSING, LineFormation.DEFAULTS(LineFormation.INTER_DISTANCE_SENSING))
  }
}

object LineFormation {
  val INTER_DISTANCE_SENSING = "interDistanceLine"
  val DEFAULTS = Map(INTER_DISTANCE_SENSING -> 0.4)
}

class CircleFormation extends ShapeFormation() {
  override def calculateSuggestion(ordered: List[(Int, (Double, Double))]): Map[Int, (Double, Double)] = {
    val division = (math.Pi * 2) / ordered.size
    val precomputedAngels = ordered.indices.map(i => division * (i + 1))
    var availableDevices = ordered
    precomputedAngels
      .map { angle =>
        val candidate = availableDevices
          .map {
            case (id, (xPos, yPos)) =>
              val newPos @ (newXpos, newYpos) = (math.sin(angle) * radius + xPos, math.cos(angle) * radius + yPos)
              (id, math.sqrt((newXpos * newXpos) + (newYpos * newYpos)), newPos)
          }
          .minBy(_._2)
        availableDevices = removeDeviceFromId(candidate._1, availableDevices)
        candidate._1 -> candidate._3
      }
      .toMap
  }

  private def radius: Double = node.getOrElse(CircleFormation.RADIUS_SENSING, CircleFormation.DEFAULTS(CircleFormation.RADIUS_SENSING))

  private def removeDeviceFromId(id: Int, devices: List[(Int, (Double, Double))]): List[(Int, (Double, Double))] = {
    devices.filterNot { case (currentId, _) =>
      currentId == id
    }
  }

  private def nearestFromPoint(point: (Double, Double), devices: List[(Int, (Double, Double))]): Int = {
    devices
      .map {
        case (id, (xPos, yPos)) =>
          val xDelta = math.abs(point._1 - xPos)
          val yDelta = math.abs(point._2 - yPos)
          id -> math.sqrt((xDelta * xDelta) + (yDelta * yDelta))
      }
      .minBy(_._1)
      ._1
  }
}

object CircleFormation {
  val RADIUS_SENSING: String = "radius"
  val DEFAULTS: Map[String, Double] = Map(RADIUS_SENSING -> 0.6)
}

class SquareFormation extends ShapeFormation() {
  override def calculateSuggestion(ordered: List[(Int, (Double, Double))]): Map[Int, (Double, Double)] = {
    if (ordered.isEmpty) return Map.empty
    val n = ordered.size
    // side length (number of points per side) to accommodate all nodes + leader
    val side = math.ceil(math.sqrt(n + 1)).toInt
    // Generate grid coordinates excluding leader position (0,0)
    val gridCoords = (for {
      y <- 0 until side
      x <- 0 until side if !(x == 0 && y == 0)
    } yield (x, y)).take(n)
    var available = ordered
    gridCoords
      .map { case (gx, gy) =>
        val candidate = available
          .map {
            case (id, (xPos, yPos)) =>
              // target absolute vector from leader for this grid cell
              val targetX = gx * distanceBetweenNodes
              val targetY = gy * distanceBetweenNodes
              // Following existing pattern, combine with current vector (acts like bias towards current pos)
              val newPos @ (newXpos, newYpos) = (targetX + xPos, targetY + yPos)
              (id, math.sqrt(newXpos * newXpos + newYpos * newYpos), newPos)
          }
          .minBy(_._2)
        available = available.filterNot(_._1 == candidate._1)
        candidate._1 -> candidate._3
      }
      .toMap
  }

  private def distanceBetweenNodes: Double = sense(SquareFormation.INTER_DISTANCE_SENSING)
}

object SquareFormation {
  val INTER_DISTANCE_SENSING = "interDistanceSquare"
  val DEFAULTS: Map[String, Double] = Map(INTER_DISTANCE_SENSING -> 0.4)
}

class VFormation extends ShapeFormation() {
  // armAngle: angle (in radians) of each arm relative to the x-axis (default suggestion: math.Pi/4)
  override def calculateSuggestion(ordered: List[(Int, (Double, Double))]): Map[Int, (Double, Double)] = {
    if (ordered.isEmpty) return Map.empty
    val n = ordered.size
    val leftCount = n / 2
    val rightCount = n - leftCount
    val dx = distanceBetweenNodes * math.cos(armAngle)
    val dy = distanceBetweenNodes * math.sin(armAngle)

    // Targets: leader apex assumed at (0,0) (leader itself not in ordered list)
    val targetsLeft = (1 to leftCount).map(k => (-k * dx, k * dy))
    val targetsRight = (1 to rightCount).map(k => (k * dx, k * dy))
    val targets = targetsLeft ++ targetsRight

    var available = ordered
    targets
      .map { case (tx, ty) =>
        val candidate = available
          .map {
            case (id, (xPos, yPos)) =>
              val newPos @ (newX, newY) = (tx + xPos, ty + yPos)
              (id, math.sqrt(newX * newX + newY * newY), newPos)
          }
          .minBy(_._2)
        available = available.filterNot(_._1 == candidate._1)
        candidate._1 -> candidate._3
      }
      .toMap
  }

  private def distanceBetweenNodes: Double = sense(VFormation.INTER_DISTANCE_SENSING)
  private def armAngle: Double = sense(VFormation.ANGLE_SENSING)
}

object VFormation {
  val INTER_DISTANCE_SENSING = "interDistanceV"
  val ANGLE_SENSING = "angleV"
  val DEFAULTS: Map[String, Double] = Map(INTER_DISTANCE_SENSING -> 0.4, ANGLE_SENSING -> - Math.PI / 4)
}

class VerticalLineFormation extends ShapeFormation() {
  // Leader at top (0,0). Other robots placed below along -Y axis at multiples of distanceThreshold.
  override def calculateSuggestion(ordered: List[(Int, (Double, Double))]): Map[Int, (Double, Double)] = {
    if (ordered.isEmpty) return Map.empty
    var available = ordered
    ordered.indices
      .map { index =>
        val candidate = available
          .map {
            case (id, (xPos, yPos)) =>
              val offsetY = - (index + 1) * distanceBetweenNodes
              val newPos @ (newX, newY) = (xPos, yPos + offsetY) // shift downward
              (id, math.sqrt(newX * newX + newY * newY), newPos)
          }
          .minBy(_._2)
        available = available.filterNot(_._1 == candidate._1)
        candidate._1 -> candidate._3
      }
      .toMap
  }

  private def distanceBetweenNodes: Double = sense(VerticalLineFormation.INTER_DISTANCE_SENSING)
}

object VerticalLineFormation {
  val INTER_DISTANCE_SENSING = "interDistanceVertical"
  val DEFAULTS = Map(INTER_DISTANCE_SENSING -> 0.4)
}