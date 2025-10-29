package it.unibo.scafi

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
