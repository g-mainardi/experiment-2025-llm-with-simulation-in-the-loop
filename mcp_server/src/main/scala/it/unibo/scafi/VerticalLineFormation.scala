package it.unibo.scafi

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
