package it.unibo.llm.mcp.server

import it.unibo.llm.mcp.server.utils.NodePosition
import it.unibo.llm.mcp.server.utils.NodePosition.NodesPositions
import it.unibo.llm.mcp.server.utils.NodePosition.TimeStampedNodesPositions
import it.unibo.llm.mcp.server.utils.ScafiTestUtils

import scala.concurrent.duration._

/** Acceptance criterion for the circle formation, computed on simulated positions. The agent never sees it: it judges
  * the final program of a run offline. Thresholds calibrated on the correct CircleFormation and the circle-bug-*
  * mutants (see the commit that set them).
  */
object FormationOracle {

  /** The node the scenario elects as leader (it doesn't move): the circle is centred on it. */
  val LeaderId = "1"

  /** Max radial RMSE of the followers, as a fraction of the target radius. */
  val RmseTolerance = 0.25

  /** Max deviation of a gap between consecutive follower angles from 2π/n, as a fraction of 2π/n. */
  val AngularTolerance = 1.5

  /** Min distance between any two robots over the whole run, as a fraction of `collisionArea`. */
  val DistanceTolerance = 0.8

  /** Time units at the end of the run where every snapshot must be within the radius and angle tolerances. */
  val SteadyState = 500.0

  // The scenario of the evaluation: the YAML defaults with a jittered grid, so each seed starts from a different
  // layout, and twice the run, because the correct program converges as late as t=3000. Three seeds, not ten: on the
  // 13 records judged with ten, every program passed or failed on all of them, and the evaluation takes a third of
  // the time; a program at the edge of a threshold (it happens on the angular gap and the min distance) can now pass
  // where ten seeds would have caught it
  val Seeds: List[Int] = (0 to 2).toList

  val Jitter = 0.1

  val EndTime = 6000

  val ExportInterval = 10

  private val Yaml = List(
    s"variables: { endTime: { type: ArbitraryVariable, parameters: [ $EndTime, [ $EndTime ] ] } }",
    s"variables: { exportInterval: { formula: $ExportInterval } }"
  )

  /** Circle metrics of one snapshot. `angularGap` is the largest deviation of a gap between consecutive followers from
    * 2π/n, relative to 2π/n; `minDistance` is between any two nodes, leader included.
    */
  final case class Snapshot(meanRadius: Double, rmse: Double, angularGap: Double, minDistance: Double)

  /** A whole run: mean radius at the end, worst RMSE and angular gap in the steady state, min distance over the whole
    * run, and the first time from which the RMSE stays within the tolerance.
    */
  final case class Circle(
      end: Double,
      meanRadius: Double,
      rmse: Double,
      angularGap: Double,
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

  def circle(trace: TimeStampedNodesPositions, radius: Double, collisionArea: Double): Circle = {
    val timeline    = trace.toIndexedSeq.sortBy(_._1).map { case (t, nodes) => t -> snapshot(nodes, radius) }
    val end         = timeline.last._1
    val steady      = timeline.filter(_._1 >= end - SteadyState).map(_._2)
    val rmse        = steady.map(_.rmse).max
    val angularGap  = steady.map(_.angularGap).max
    val minDistance = timeline.map(_._2.minDistance).min
    val tolerance   = RmseTolerance * radius
    val convergence = timeline.indices.find(k => timeline.drop(k).forall(_._2.rmse <= tolerance)).map(timeline(_)._1)
    val passed      =
      rmse <= tolerance && angularGap <= AngularTolerance && minDistance >= DistanceTolerance * collisionArea
    Circle(end, timeline.last._2.meanRadius, rmse, angularGap, minDistance, convergence, passed)
  }

  /** Simulates `program` in the evaluation scenario with the given seed and target radius. Left: why it could not be
    * judged (compilation or simulation error, timeout).
    */
  def evaluate(
      program: String,
      radius: Double,
      seed: Int,
      timeout: Duration = 5.minutes
  ): Either[List[String], Circle] =
    ScafiTestUtils
      .simulate(program, timeout, Map("seed" -> seed, "jitter" -> Jitter, "radius" -> radius), Yaml)
      .flatMap { trace =>
        // A timeout leaves a shorter run: no steady state to judge
        if (trace.failed || trace.notes.nonEmpty) Left(trace.notes)
        else Right(circle(trace.positions, radius, collisionArea))
      }

  /** The scenario default, which the evaluation keeps. */
  def collisionArea: Double = ScafiTestUtils.variables("collisionArea").getDefault.toString.toDouble

  private def distance(a: NodePosition, b: NodePosition): Double = math.hypot(a.x - b.x, a.y - b.y)

}
