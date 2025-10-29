package it.unibo.scafi

import it.unibo.alchemist.action.DirectionalRobotProperty
import it.unibo.scafi.Actuation.{Forward, NoOp, Rotation}
import it.unibo.scafi.space.Point3D
import it.unibo.scafi.space.optimization.RichPoint3D


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
    val property = alchemistEnvironment.getNodeByID(mid()).asProperty[DirectionalRobotProperty[Any]](classOf[DirectionalRobotProperty[Any]])
    val leaderOrientation = broadcast(leader, property.getAngle)
    val localGoal = computeLocalGoal(leader, leaderSelected, directionTowardsLeader)
    val neighborMap = buildNeighborMap()
    val avoidance = computeAvoidanceVector(neighborMap, collisionArea)
    val result = determineActuation(leader, localGoal, avoidance, leaderOrientation, stabilityThreshold)
    node.put("actuation", result)
    node.put("direction", Array(property.orientation._1, property.orientation._2))
    node.put("mid", mid())

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
    broadcast(leader, suggestion).getOrElse(mid(), (0.0, 0.0))
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
    foldhoodPlus[Map[Int, (Double, Double)]](Map.empty)((a, b) => a ++ b)(Map(nbr(mid()) -> distanceVector))
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









