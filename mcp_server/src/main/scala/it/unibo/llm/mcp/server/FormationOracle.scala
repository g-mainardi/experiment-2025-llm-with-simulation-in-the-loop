package it.unibo.llm.mcp.server

import it.unibo.llm.mcp.server.utils.NodePosition
import it.unibo.llm.mcp.server.utils.NodePosition.NodesPositions
import it.unibo.llm.mcp.server.utils.NodePosition.TimeStampedNodesPositions
import it.unibo.llm.mcp.server.utils.ScafiTestUtils

import scala.concurrent.duration._

/** Acceptance criteria for the formations, computed on simulated positions. The agent never sees them: they judge the
  * final program of a run offline. The circle thresholds are calibrated on the correct CircleFormation and the
  * circle-bug-* mutants (see the commit that set them), the slot shapes ones on LineFormation, SquareFormation and
  * VFormation and their negatives (half spacing, a 5x3 grid).
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

  /** Shapes judged on slots, i.e. ideal follower positions relative to the leader. */
  val SlotShapes: Set[String] = Set("line", "square", "v")

  /** Max Hausdorff distance between followers and slots, as a fraction of the spacing. The references stay within 0.1
    * (the stabilityThreshold: a robot stops within 0.1 of its goal) at spacing 0.4 on seeds 0-9, the closest negative
    * (a 5x3 grid instead of 4x4) at 0.46 or more.
    */
  val HausdorffTolerance = 0.5

  /** A slot shape judged on a whole run: worst Hausdorff distance and min distance in the steady state, and the first
    * time from which the Hausdorff distance stays within the tolerance. The min distance is not over the whole run as
    * for the circle: the reference line comes as close as 0.088 (and the V 0.146) while the robots cross on their way
    * to the slots, a transient that comes from the repulsion in ShapeFormation, not from the shape; in the steady state
    * the references stay at 0.29 or more.
    */
  final case class Slots(
      end: Double,
      hausdorff: Double,
      minDistance: Double,
      convergence: Option[Double],
      passed: Boolean
  )

  /** The slots of `n` followers, one list per admitted variant: the shapes of the reference formations, with the extra
    * robot of the line and the V on either side and the leader in any corner of the square.
    */
  def slots(shape: String, spacing: Double, n: Int): List[List[(Double, Double)]] = {
    def mirror(slots: List[(Double, Double)]) = slots.map { case (x, y) => (-x, y) }
    // Line and V: n / 2 robots on the left, the others on the right, `spacing` apart along the arm
    def arms(dx: Double, dy: Double) =
      (1 to n / 2).map(k => (-k * dx, k * dy)) ++ (1 to n - n / 2).map(k => (k * dx, k * dy))
    shape match {
      case "line" =>
        val line = arms(spacing, 0).toList
        List(line, mirror(line))
      case "v" =>
        // Leader at the apex, arms going down at 45 degrees
        val v = arms(spacing / math.sqrt(2), -spacing / math.sqrt(2)).toList
        List(v, mirror(v))
      case "square" =>
        // Full grid, the leader in the corner (0, 0), filled row by row
        val side   = math.ceil(math.sqrt(n + 1)).toInt
        val square = (for {
          y <- 0 until side
          x <- 0 until side if x != 0 || y != 0
        } yield (x * spacing, y * spacing)).take(n).toList
        for {
          sx <- List(1, -1)
          sy <- List(1, -1)
        } yield square.map { case (x, y) => (sx * x, sy * y) }
    }
  }

  /** Hausdorff distance between the followers, relative to the leader, and the nearest variant of the slots. */
  def hausdorff(nodes: NodesPositions, variants: List[List[(Double, Double)]]): Double = {
    val leader                                                        = nodes(LeaderId)
    val followers                                                     = (nodes - LeaderId).values.toList.map(p => (p.x - leader.x, p.y - leader.y))
    def far(from: List[(Double, Double)], to: List[(Double, Double)]) =
      from.map { case (x, y) => to.map { case (u, v) => math.hypot(x - u, y - v) }.min }.max
    variants.map(slots => math.max(far(slots, followers), far(followers, slots))).min
  }

  def slotShape(trace: TimeStampedNodesPositions, shape: String, spacing: Double, collisionArea: Double): Slots = {
    val ordered     = trace.toIndexedSeq.sortBy(_._1)
    val variants    = slots(shape, spacing, ordered.head._2.size - 1)
    val timeline    = ordered.map { case (t, nodes) => (t, hausdorff(nodes, variants), nodes) }
    val end         = timeline.last._1
    val steady      = timeline.filter(_._1 >= end - SteadyState)
    val worst       = steady.map(_._2).max
    val minDistance = steady.map(s => minimumDistance(s._3)).min
    val tolerance   = HausdorffTolerance * spacing
    val convergence = timeline.indices.find(k => timeline.drop(k).forall(_._2 <= tolerance)).map(timeline(_)._1)
    Slots(end, worst, minDistance, convergence, worst <= tolerance && minDistance >= DistanceTolerance * collisionArea)
  }

  def snapshot(nodes: NodesPositions, radius: Double): Snapshot = {
    val leader    = nodes(LeaderId)
    val followers = (nodes - LeaderId).values.toIndexedSeq
    val radii     = followers.map(p => math.hypot(p.x - leader.x, p.y - leader.y))
    val rmse      = math.sqrt(radii.map(d => (d - radius) * (d - radius)).sum / radii.size)
    val angles    = followers.map(p => math.atan2(p.y - leader.y, p.x - leader.x)).sorted
    val gaps      = angles.zip(angles.tail :+ (angles.head + 2 * math.Pi)).map { case (a, b) => b - a }
    val ideal     = 2 * math.Pi / followers.size
    Snapshot(radii.sum / radii.size, rmse, gaps.map(g => math.abs(g - ideal)).max / ideal, minimumDistance(nodes))
  }

  /** Between any two nodes, leader included. */
  private def minimumDistance(nodes: NodesPositions): Double = {
    val all = nodes.values.toIndexedSeq
    (for {
      i <- all.indices
      j <- all.indices if i < j
    } yield distance(all(i), all(j))).min
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
    run(program, seed, timeout, Map("radius" -> radius)).map(circle(_, radius, collisionArea))

  /** As `evaluate`, for a slot shape with the given spacing, on the scenario defaults. */
  def evaluateSlots(
      program: String,
      shape: String,
      spacing: Double,
      seed: Int,
      timeout: Duration = 5.minutes
  ): Either[List[String], Slots] =
    run(program, seed, timeout).map(slotShape(_, shape, spacing, collisionArea))

  /** The positions of `program` in the evaluation scenario. Left: why it could not be judged. */
  def run(
      program: String,
      seed: Int,
      timeout: Duration,
      parameters: Map[String, Any] = Map.empty
  ): Either[List[String], TimeStampedNodesPositions] =
    ScafiTestUtils.simulate(program, timeout, parameters ++ Map("seed" -> seed, "jitter" -> Jitter), Yaml).flatMap {
      trace =>
        // A timeout leaves a shorter run: no steady state to judge
        if (trace.failed || trace.notes.nonEmpty) Left(trace.notes) else Right(trace.positions)
    }

  /** The scenario default, which the evaluation keeps. */
  def collisionArea: Double = ScafiTestUtils.variables("collisionArea").getDefault.toString.toDouble

  private def distance(a: NodePosition, b: NodePosition): Double = math.hypot(a.x - b.x, a.y - b.y)

}
