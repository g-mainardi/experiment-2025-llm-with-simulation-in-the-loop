package it.unibo.scafi

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
