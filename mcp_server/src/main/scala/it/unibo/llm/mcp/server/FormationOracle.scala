package it.unibo.llm.mcp.server

import it.unibo.llm.mcp.server.utils.NodePosition
import it.unibo.llm.mcp.server.utils.NodePosition.NodesPositions
import it.unibo.llm.mcp.server.utils.NodePosition.TimeStampedNodesPositions

/** Acceptance criterion for the circle formation, computed on simulated positions. The agent never sees it: it judges
  * the final program of a run offline.
  */
object FormationOracle {

  /** The node the scenario elects as leader (it doesn't move): the circle is centred on it. */
  val LeaderId = "1"

  /** Max gap between consecutive follower angles, as a fraction of the ideal 2π/n. */
  val AngularTolerance = 0.5

  /** Circle metrics of one snapshot. `angularGap` is the largest deviation of a gap between consecutive followers from
    * 2π/n, relative to 2π/n; `minDistance` is between any two nodes, leader included.
    */
  final case class Snapshot(meanRadius: Double, rmse: Double, angularGap: Double, minDistance: Double)

  /** A whole run: the last snapshot, the minimum distance over all snapshots and the first time from which the RMSE
    * stays within the tolerance.
    */
  final case class Circle(
      end: Double,
      last: Snapshot,
      minDistance: Double,
      convergence: Option[Double],
      passed: Boolean
  )

  def snapshot(nodes: NodesPositions, radius: Double): Snapshot = {
    val leader    = nodes(LeaderId)
    val all       = nodes.values.toIndexedSeq
    val followers = (nodes - LeaderId).values.toIndexedSeq
    val radii     = followers.map(p => math.hypot(p.x - leader.x, p.y - leader.y))
    val rmse      = math.sqrt(radii.map(d => (d - radius) * (d - radius)).sum / radii.size)
    val angles    = followers.map(p => math.atan2(p.y - leader.y, p.x - leader.x)).sorted
    val gaps      = angles.zip(angles.tail :+ (angles.head + 2 * math.Pi)).map { case (a, b) => b - a }
    val ideal     = 2 * math.Pi / followers.size
    val distances = for {
      i <- all.indices
      j <- all.indices if i < j
    } yield distance(all(i), all(j))
    Snapshot(radii.sum / radii.size, rmse, gaps.map(g => math.abs(g - ideal)).max / ideal, distances.min)
  }

  /** `tolerance` bounds the RMSE, `collisionArea` the minimum distance. */
  def circle(trace: TimeStampedNodesPositions, radius: Double, collisionArea: Double, tolerance: Double): Circle = {
    val timeline    = trace.toIndexedSeq.sortBy(_._1).map { case (t, nodes) => t -> snapshot(nodes, radius) }
    val (end, last) = timeline.last
    val minDistance = timeline.map(_._2.minDistance).min
    val convergence = timeline.indices.find(k => timeline.drop(k).forall(_._2.rmse <= tolerance)).map(timeline(_)._1)
    val passed      = last.rmse <= tolerance && last.angularGap <= AngularTolerance && minDistance >= collisionArea
    Circle(end, last, minDistance, convergence, passed)
  }

  private def distance(a: NodePosition, b: NodePosition): Double = math.hypot(a.x - b.x, a.y - b.y)

}
