package it.unibo.llm.mcp.server

import it.unibo.alchemist.boundary.LoadAlchemist
import it.unibo.alchemist.model.terminators.AfterTime
import it.unibo.alchemist.model.times.DoubleTime
import it.unibo.llm.mcp.server.utils.ScafiTestUtils
import org.kaikikm.threadresloader.ResourceLoader

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import scala.concurrent.duration._
import scala.io.Source
import scala.jdk.CollectionConverters._

/** Measures the `simulate_code` pipeline, computes the circle acceptance metrics and checks which code is actually
  * simulated.
  *
  * Usage: `./mill mcp_server.test.runMain it.unibo.llm.mcp.server.SimulationProbe <CircleFormation.scala> [output dir]`
  * (absolute paths).
  */
object SimulationProbe {

  // Scenario of swarmSimulation.yml and acceptance criterion thresholds (to be calibrated)
  private val LeaderId = 1

  private val Radius = 0.6 // CircleFormation default

  private val RadiusTolerance = 0.1 // = stabilityThreshold

  private val AngularTolerance = 0.5 // max gap between consecutive angles, as a fraction of 2π/n

  private val CollisionArea = 0.2

  private val EndTime = 3000.0

  private val ExportInterval = 10

  private type Positions = IndexedSeq[(Double, Double)]

  final private case class Metrics(rmse: Double, angular: Double, minDist: Double, meanRadius: Double)

  final private case class Run(
      compileMs: Long,
      simMs: Long,
      origin: String,
      samples: Seq[(Double, Positions)],
      error: Option[String]
  )

  def main(args: Array[String]): Unit = {
    val source = Files.readString(Paths.get(args(0)))
    val out    = args.lift(1).map(Paths.get(_)).getOrElse(Files.createTempDirectory("probe-evidence"))
    Files.createDirectories(out)
    val bigRadius = source.replace("RADIUS_SENSING -> 0.6", "RADIUS_SENSING -> 2.0")
    val renamed   = bigRadius.replace("CircleFormation", "ProbeCircleFormation")
    val broken    = source.replace("division * (i + 1)", "division * \"one\"")
    println(s"Java ${System.getProperty("java.version")}, output in $out")

    println("\n== A. MCP tool cost")
    val (coldMs, _) = time(ScafiTestUtils.compileAndGetErrors(source))
    val (warmMs, _) = time(ScafiTestUtils.compileAndGetErrors(source))
    println(s"compile_code: $coldMs ms (first call), $warmMs ms (next call)")

    println("\n== B. Actual tool path: ScafiTestUtils.simulateProgram, as in simulate_code")
    val original = viaTool("B1 original", source, out.resolve("plot-original.png"))
    val modified = viaTool("B2 same class, radius 2.0", bigRadius, out.resolve("plot-radius-2.0.png"))
    if (original.nonEmpty && original == modified)
      println("=> B1 and B2 produced the SAME PNG, byte for byte: the radius change was ignored")
    else if (original.nonEmpty && modified.nonEmpty) println("=> B1 and B2 produced DIFFERENT PNGs")
    val notCompiling = viaTool("B3 program that does not compile", broken, out.resolve("plot-not-compiling.png"))
    viaTool("B4 renamed class, radius 2.0", renamed, out.resolve("plot-renamed.png"))
    val radius3 = source.replace("RADIUS_SENSING -> 0.6", "RADIUS_SENSING -> 3.0")
    val third   = viaTool("B5 same class, radius 3.0 (after B2)", radius3, out.resolve("plot-radius-3.0.png"))
    if (third.nonEmpty && third == modified)
      println("=> B5 same as B2: the class of the previous simulation was reused")
    else if (third.nonEmpty && third != original)
      println("=> B5 differs from B1 and B2: each simulation runs its own code")

    println(s"\n== C. Replica of the ORIGINAL pipeline, before the fix (export every $ExportInterval time units)")
    println("-- C1 original")
    val reference = instrumentedRun(source, registerWithAlchemist = false)
    report(reference)
    println("-- C2 same class, radius 2.0")
    report(instrumentedRun(bigRadius, registerWithAlchemist = false))
    println("-- C3 renamed class, radius 2.0")
    report(instrumentedRun(renamed, registerWithAlchemist = false))

    // Last, because ResourceLoader.addURL changes the state of this thread's Alchemist classloader
    println("\n== D. Previous fix attempt (ResourceLoader.addURL, never reset)")
    println("-- D1 renamed class, radius 2.0")
    report(instrumentedRun(renamed, registerWithAlchemist = true))
    println("-- D2 same class with unique package (package it.unibo.scafi / package probe1), radius 2.0")
    val uniquePackage =
      bigRadius.replaceFirst("(?m)^package it\\.unibo\\.scafi\\s*$", "package it.unibo.scafi\npackage probe1")
    report(instrumentedRun(uniquePackage, registerWithAlchemist = true))
    // addURL accumulates: does a second run with the same package still see the class of the first one?
    val probe1Radius3 = uniquePackage.replace("RADIUS_SENSING -> 2.0", "RADIUS_SENSING -> 3.0")
    println("-- D3 same package probe1 reused, radius 3.0 (expected ~3.0 without contamination)")
    report(instrumentedRun(probe1Radius3, registerWithAlchemist = true))
    println("-- D4 new package probe2, radius 3.0")
    report(instrumentedRun(probe1Radius3.replace("package probe1", "package probe2"), registerWithAlchemist = true))
    // Exit code for CI: section B must show that each submission is simulated and compile errors are reported
    val shas   = Seq(original, modified, third)
    val passed = shas.forall(_.nonEmpty) && shas.distinct.size == shas.size && notCompiling.isEmpty
    println(s"\n== Result: ${if (passed) "PASSED" else "FAILED"}")
    sys.exit(if (passed) 0 else 1)
  }

  /** Calls the tool code and saves the returned PNG; returns the PNG SHA-256 hash (empty on error). */
  private def viaTool(label: String, program: String, png: Path): String = {
    println(s"-- $label")
    try {
      val (ms, (failed, errors, image)) = time(ScafiTestUtils.simulateProgram(program, 60.seconds))
      if (failed) { println(s"error returned by the tool ($ms ms):\n  ${errors.mkString("\n  ")}"); "" }
      else {
        val bytes = Base64.getDecoder.decode(image.get)
        Files.write(png, bytes)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString
        println(s"ok in $ms ms, PNG saved to ${png.getFileName}, SHA-256 $sha")
        sha
      }
    } catch {
      case e: Exception => println(s"exception from the tool: ${e.toString.linesIterator.next()}"); ""
    }
  }

  /** Same pipeline as the original ScafiTestUtils.simulateProgram, with timed phases and a denser export. */
  private def instrumentedRun(program: String, registerWithAlchemist: Boolean): Run = {
    val dir = Files.createTempDirectory("scafi-probe-")
    val pkg = """(?m)^\s*package\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)""".r
      .findAllMatchIn(program)
      .map(_.group(1) + ".")
      .mkString
    val fqn = pkg + """(?m)^\s*(?:.*?\s)?(?:case\s+)?class\s+([A-Za-z_]\w*)\b""".r
      .findFirstMatchIn(program)
      .get
      .group(1)

    val (compileMs, (hasErrors, errors)) = time(ScafiTestUtils.compile(program, dir))
    if (hasErrors) return Run(compileMs, 0, "-", Nil, Some(errors.mkString("; ")))
    // Classloader RunScafiProgram uses to resolve the program name
    if (registerWithAlchemist) ResourceLoader.addURL(dir.toUri.toURL)

    val parent = Thread.currentThread.getContextClassLoader
    val loader = new URLClassLoader(Array(dir.toUri.toURL), parent)
    Thread.currentThread.setContextClassLoader(loader)
    try {
      val loaded    = Class.forName(fqn, true, loader)
      val origin    = Option(loaded.getProtectionDomain.getCodeSource).fold("unknown")(_.getLocation.toString)
      val exportDir = dir.resolve("export")
      val spec      = Source
        .fromResource("swarmSimulation.yml")
        .mkString
        .replace("{{ }}", fqn)
        .replace("{{EXPORT_DIR}}", exportDir.toString)
        .replace("interval: 330", s"interval: $ExportInterval")
      val yml            = Files.writeString(dir.resolve("probe.yml"), spec)
      val (simMs, error) = time {
        val simulation = LoadAlchemist.from(yml.toFile).getDefault
        simulation.getEnvironment.addTerminator(new AfterTime(new DoubleTime(EndTime)))
        simulation.play()
        simulation.run()
        Option(simulation.getError.getOrNull).map(_.toString)
      }
      val csv     = exportDir.resolve("experiment.csv")
      val samples =
        if (!Files.exists(csv)) Nil
        else
          Files
            .readAllLines(csv)
            .asScala
            .toList
            .filterNot(line => line.startsWith("#") || line.isBlank)
            .map { line =>
              val values = line.trim.split("\\s+").map(_.toDouble)
              values.head -> values.tail.grouped(2).map(p => (p(0), p(1))).toIndexedSeq
            }
      Run(compileMs, simMs, s"$fqn from $origin (compiled in ${dir.toUri})", samples, error)
    } catch {
      case e: Exception => Run(compileMs, 0, fqn, Nil, Some(e.toString.linesIterator.next()))
    } finally {
      Thread.currentThread.setContextClassLoader(parent)
      loader.close()
    }
  }

  private def metrics(ps: Positions): Metrics = {
    val (lx, ly)  = ps(LeaderId)
    val followers = ps.indices.filter(_ != LeaderId).map(ps)
    val radii     = followers.map { case (x, y) => math.hypot(x - lx, y - ly) }
    val rmse      = math.sqrt(radii.map(d => (d - Radius) * (d - Radius)).sum / radii.size)
    val angles    = followers.map { case (x, y) => math.atan2(y - ly, x - lx) }.sorted
    val gaps      = angles.zip(angles.tail :+ (angles.head + 2 * math.Pi)).map { case (a, b) => b - a }
    val ideal     = 2 * math.Pi / followers.size
    val angular   = gaps.map(g => math.abs(g - ideal)).max / ideal
    val minDist   = (for {
      i <- ps.indices
      j <- ps.indices if i < j
    } yield math.hypot(ps(i)._1 - ps(j)._1, ps(i)._2 - ps(j)._2)).min
    Metrics(rmse, angular, minDist, radii.sum / radii.size)
  }

  private def report(run: Run): Unit = {
    println(s"Class: ${run.origin}")
    println(s"Compilation: ${run.compileMs} ms, simulation: ${run.simMs} ms")
    run.error.foreach(e => println(s"ERROR: $e"))
    if (run.samples.nonEmpty) {
      val timeline    = run.samples.map { case (t, ps) => t -> metrics(ps) }
      val (end, last) = timeline.last
      val minDist     = timeline.map(_._2.minDist).min
      val converged   = timeline.indices
        .find(k => timeline.drop(k).forall(_._2.rmse <= RadiusTolerance))
        .map(k => f"t=${timeline(k)._1}%.0f")
      println(
        f"t=$end%.0f  mean radius=${last.meanRadius}%.3f  radial RMSE=${last.rmse}%.3f  " +
          f"angular gap=${last.angular}%.2f  min distance in run=$minDist%.3f"
      )
      println(s"Convergence (RMSE ≤ $RadiusTolerance from then on): ${converged.getOrElse("never")}")
      val pass = last.rmse <= RadiusTolerance && last.angular <= AngularTolerance && minDist >= CollisionArea
      println(s"Acceptance criterion: ${if (pass) "PASSED" else "NOT passed"}")
    }
  }

  private def time[A](block: => A): (Long, A) = {
    val start  = System.nanoTime()
    val result = block
    ((System.nanoTime() - start) / 1000000, result)
  }

}
