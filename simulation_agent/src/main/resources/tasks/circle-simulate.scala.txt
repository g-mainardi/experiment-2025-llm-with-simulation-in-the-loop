package it.unibo.scafi

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
