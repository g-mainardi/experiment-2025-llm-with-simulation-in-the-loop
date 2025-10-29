package it.unibo.scafi

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
