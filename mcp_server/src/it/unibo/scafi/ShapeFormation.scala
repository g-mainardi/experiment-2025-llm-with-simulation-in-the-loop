package it.unibo.scafi

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
    val leaderSelected = sense[Int] ("leader")
    val stabilityThreshold = sense[Double] ("stabilityThreshold")
    val collisionArea = sense[Double] ("collisionArea")
    val leader = mid () == leaderSelected
    val potential = classicGradient(leader)
    val directionTowardsLeader = G[(Double, Double)](
      source = leader,
      field = (0.0, 0.0),
      acc = { case (x, y) => (x + distanceVector._1, y + distanceVector._2) },
      metric = nbrRange
    )
    val leaderOrientation = broadcast(leader, sense[Double]("orientation"))
    val collectInfo = C[Double, Map[Int, (Double, Double)]] (potential, _++ _, Map (mid () -> directionTowardsLeader), Map.empty)
  .filter (_._1 != mid () )
    val ordered = orderedNodes (collectInfo.toSet)
    val suggestion = branch (leaderSelected == mid () ) (calculateSuggestion (ordered) ) (Map.empty)
    val local = broadcast(leader, suggestion).getOrElse(mid, (0.0, 0.0) )
    val distanceTowardGoal = Math.sqrt (local._1 * local._1 + local._2 * local._2)
    val neighborMap = foldhoodPlus[Map[Int, (Double, Double)]](Map.empty)((a, b) => a ++ b)(Map (nbr (mid) -> distanceVector))
      .map { case (id, nbrVector) => id -> Point3D (nbrVector._1, nbrVector._2, 0.0)}
    // convert the orientation to a 2d vector
    val (orientationLeaderX, orientationLeaderY) = (- math.sin (leaderOrientation), math.cos (leaderOrientation) )
    // Aggregate repulsion from all neighbors within collisionRange (inverse-square weighting)
    val repulsionSum = computeRepulsionSum (neighborMap, collisionArea)
    val avoidance = if(repulsionSum.magnitude > maxRepulsion) {
      repulsionSum.normalize * maxRepulsion
    } else {
      repulsionSum
    }
    val resultingVector = ((Point3D (local._1, local._2, 0) ) + avoidance).normalize
    val res = if (distanceTowardGoal < stabilityThreshold) {
      if (leader) {
        NoOp
      } else {
        computeGoalConsideringAvoidance((orientationLeaderX, orientationLeaderY), avoidance)
      }
    } else {
      Forward((resultingVector.x, resultingVector.y) )
    }
    res
  }
  protected def orderedNodes(nodes: Set[(Int, (Double, Double))]): List[(Int, (Double, Double))] =
    nodes.filter(_._1 != mid()).toList.sortBy(_._1)

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

  private def computeGoalConsideringAvoidance(leaderOrientation: (Double, Double), avoidance: Point3D): Actuation =
    if(avoidance.magnitude > 0.01) {
      val combinedVector = (Point3D(leaderOrientation._1, leaderOrientation._2, 0) + avoidance).normalize
      Forward((combinedVector.x, combinedVector.y))
    } else {
      Rotation(leaderOrientation._1, leaderOrientation._2)
    }

  def calculateSuggestion(ordered: List[(Int, (Double, Double))]): Map[Int, (Double, Double)]
}