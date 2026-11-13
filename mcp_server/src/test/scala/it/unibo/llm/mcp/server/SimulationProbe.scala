package it.unibo.llm.mcp.server

import it.unibo.llm.mcp.server.utils.ScafiTestUtils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import scala.concurrent.duration._

/** Measures the `simulate_code` pipeline, checks which code is actually simulated and prints the circle metrics of the
  * program and of the circle-bug-* mutants, then judges the reference line, square and V (the files next to it) and the
  * program itself against every shape.
  *
  * Usage: `./mill mcp_server.test.runMain it.unibo.llm.mcp.server.SimulationProbe <CircleFormation.scala> [output dir]`
  * (absolute paths).
  */
object SimulationProbe {

  // Target radius: the scenario default
  private val Radius = scenarioDefault("radius")

  // Spacing of the slot shapes, as in the reference formations
  private val Spacing = 0.4

  // One seed of the oracle's, to keep the CI run short
  private val ProbeSeed = 0

  def main(args: Array[String]): Unit = {
    val source = Files.readString(Paths.get(args(0)))
    val out    = args.lift(1).map(Paths.get(_)).getOrElse(Files.createTempDirectory("probe-evidence"))
    Files.createDirectories(out)
    // The scenario sets the radius molecule, so the default in the source has no effect: change the formula instead
    val bigRadius = source.replace("* radius", "* 2.0")
    val renamed   = bigRadius.replace("CircleFormation", "ProbeCircleFormation")
    val broken    = source.replace("division * (i + 1)", "division * \"one\"")
    println(s"Java ${System.getProperty("java.version")}, output in $out")

    println("\n== A. MCP tool cost")
    val (coldMs, _) = time(ScafiTestUtils.compileAndGetErrors(source))
    val (warmMs, _) = time(ScafiTestUtils.compileAndGetErrors(source))
    println(s"compile_code: $coldMs ms (first call), $warmMs ms (next call)")

    println("\n== B. Actual tool path: ScafiTestUtils.simulateProgram, as in simulate_code")
    val original = viaTool("B1 original", source, out.resolve("plot-original.png"))
    // Through the scenario variable: checks the chain parameters → getWith → molecule → param
    val modified = viaTool(
      "B2 same class, radius 2.0 as parameter",
      source,
      out.resolve("plot-radius-2.0.png"),
      Map("radius" -> 2.0)
    )
    if (original.nonEmpty && original == modified)
      println("=> B1 and B2 produced the SAME PNG, byte for byte: the radius change was ignored")
    else if (original.nonEmpty && modified.nonEmpty) println("=> B1 and B2 produced DIFFERENT PNGs")
    val notCompiling = viaTool("B3 program that does not compile", broken, out.resolve("plot-not-compiling.png"))
    viaTool("B4 renamed class, radius 2.0", renamed, out.resolve("plot-renamed.png"))
    val radius3 = source.replace("* radius", "* 3.0")
    val third   = viaTool("B5 same class, radius 3.0 (after B2)", radius3, out.resolve("plot-radius-3.0.png"))
    if (third.nonEmpty && third == modified)
      println("=> B5 same as B2: the class of the previous simulation was reused")
    else if (third.nonEmpty && third != original)
      println("=> B5 differs from B1 and B2: each simulation runs its own code")
    // Issue #23: the class to simulate was the first word after `class`, also in comments, strings and a redefined
    // ShapeFormation
    val decoys = mutant(
      source,
      "class CircleFormation",
      "/* class Dummy */\nval note = \"class Fake\"\n" +
        "abstract class ShapeFormation extends it.unibo.scafi.ShapeFormation\nclass CircleFormation"
    )
    val decoyed =
      viaTool("B6 class in a comment and a string, ShapeFormation redefined", decoys, out.resolve("plot-decoys.png"))

    println(
      s"\n== C. Oracle on the tool pipeline: seed $ProbeSeed, jitter ${FormationOracle.Jitter}, " +
        s"t=${FormationOracle.EndTime} (Evaluate uses seeds ${FormationOracle.Seeds.mkString(",")})"
    )
    val correctPasses = circleMetrics("C1 original", source)
    // The starting programs of the circle-bug-* agent tasks, one bug each
    val mutantsPass = List(
      circleMetrics(
        "C2 half circle (math.Pi / n)",
        mutant(source, "(math.Pi * 2) / ordered.size", "math.Pi / ordered.size")
      ),
      circleMetrics("C3 farthest node (maxBy)", mutant(source, ".minBy(_._2)", ".maxBy(_._2)")),
      circleMetrics(
        "C4 absolute coordinates (no + xPos / yPos)",
        mutant(source, "* radius + xPos, math.cos(angle) * radius + yPos", "* radius, math.cos(angle) * radius")
      )
    )
    println(s"\n== D. Slot shapes: each reference against every shape, seed $ProbeSeed")
    // The references next to CircleFormation in it.unibo.scafi; CircleFormation is the starting program of the shape
    // tasks, so it must fail them all
    val references = List("line" -> "LineFormation", "square" -> "SquareFormation", "v" -> "VFormation")
    val slotsRight = (references :+ ("circle" -> "CircleFormation")).map { case (own, name) =>
      val program = Files.readString(Paths.get(args(0)).resolveSibling(s"$name.scala"))
      shapeMetrics(name, own, program)
    }
    // Exit code for CI: section B must show that each submission is simulated and compile errors are reported,
    // section C that the oracle accepts the correct program and rejects the mutants
    val shas   = Seq(original, modified, third)
    val passed =
      shas.forall(_.nonEmpty) && shas.distinct.size == shas.size && notCompiling.isEmpty && decoyed.nonEmpty &&
        correctPasses && !mutantsPass.contains(true) && slotsRight.forall(identity)
    println(s"\n== Result: ${if (passed) "PASSED" else "FAILED"}")
    sys.exit(if (passed) 0 else 1)
  }

  /** Calls the tool code and saves the returned PNG; returns the PNG SHA-256 hash (empty on error). */
  private def viaTool(label: String, program: String, png: Path, parameters: Map[String, Any] = Map.empty): String = {
    println(s"-- $label")
    try {
      val (ms, (failed, errors, image)) =
        time(ScafiTestUtils.simulateProgram(program, 60.seconds, parameters))
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

  /** `source` with `from` replaced: fails if `from` is not there, so the mutant can't silently be the original. */
  private def mutant(source: String, from: String, to: String): String = {
    require(source.contains(from), s"Mutation not applicable, '$from' not found")
    source.replace(from, to)
  }

  /** Prints the oracle metrics of `program` with the default radius; returns whether it passed. */
  /** Simulates `program` once and judges it against every slot shape and the circle: it must pass exactly `own`.
    * Returns whether it did.
    */
  private def shapeMetrics(name: String, own: String, program: String): Boolean = {
    println(s"-- $name")
    FormationOracle.run(program, ProbeSeed, 5.minutes) match {
      case Left(errors) => println(s"ERROR: ${errors.mkString("; ")}"); false
      case Right(trace) =>
        val circle  = FormationOracle.circle(trace, Radius, FormationOracle.collisionArea)
        val verdict = FormationOracle.SlotShapes.toList.sorted.map { shape =>
          val s = FormationOracle.slotShape(trace, shape, Spacing, FormationOracle.collisionArea)
          println(
            f"$shape%-6s Hausdorff=${s.hausdorff}%.3f min distance=${s.minDistance}%.3f " +
              s"convergence=${s.convergence
                  .fold("never")(t => f"t=$t%.0f")} ${if (s.passed) "PASSED" else "not passed"}"
          )
          s.passed == (shape == own)
        }
        println(s"circle passed: ${circle.passed}")
        verdict.forall(identity) && circle.passed == (own == "circle")
    }
  }

  private def circleMetrics(label: String, program: String): Boolean = {
    println(s"-- $label")
    FormationOracle.evaluate(program, Radius, ProbeSeed) match {
      case Left(errors) => println(s"ERROR: ${errors.mkString("; ")}"); false
      case Right(c)     =>
        println(
          f"t=${c.end}%.0f  mean radius=${c.meanRadius}%.3f  in the last ${FormationOracle.SteadyState}%.0f: " +
            f"max radial RMSE=${c.rmse}%.3f, max angular gap=${c.angularGap}%.2f; min distance in run=${c.minDistance}%.3f"
        )
        println(s"Convergence: ${c.convergence.fold("never")(t => f"t=$t%.0f")}")
        println(s"Acceptance criterion: ${if (c.passed) "PASSED" else "NOT passed"}")
        c.passed
    }
  }

  private def scenarioDefault(name: String): Double = ScafiTestUtils.variables(name).getDefault.toString.toDouble

  private def time[A](block: => A): (Long, A) = {
    val start  = System.nanoTime()
    val result = block
    ((System.nanoTime() - start) / 1000000, result)
  }

}
