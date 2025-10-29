package it.unibo.llm.mcp.server.utils

case class NodePosition(x: Double, y: Double)
object NodePosition {
  type NodesPositions = Map[String, NodePosition]
  type TimeStampedNodesPositions = Map[Double, NodesPositions]
}
