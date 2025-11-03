package it.unibo.llm.server.utils

case class NodePosition(x: Double, y: Double)
object NodePosition {
  type NodesPositions = Map[String, NodePosition]
  type TimeStampedNodesPositions = Map[Double, NodesPositions]
}
