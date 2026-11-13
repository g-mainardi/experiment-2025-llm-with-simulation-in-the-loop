package it.unibo.llm.agent

import scala.io.Source
import scala.util.Using

/** What the final formation should look like, for checking the agent's result. */
enum Target {

  case Circle(radius: Double)

}

/** A task for the agent: the prompt piece with its goal (what the `goal` piece stands for), the program it starts from
  * (in `resources/tasks/<id>.scala.txt`) and the target formation.
  */
final case class Task(id: String, goal: String, startingProgram: Option[String], target: Target) {

  /** The prompt sent to the model: the given pieces, one paragraph each, then the starting program after the line of
    * the original prompt. Without pieces and with simulation it is the original prompt of the professor's repo; without
    * `simulate_code` the line only introduces the program, since the model can't run it.
    */
  def fullPrompt(pieces: List[String], condition: String): String = {
    val line = if (condition == "full") "Run the simulation of this:" else "Here is the program:"
    (pieces.map(name => Tasks.piece(if (name == Tasks.Goal) goal else name)) :::
      startingProgram.toList.map(program => s"$line\n$program")).mkString("\n\n")
  }

}

object Tasks {

  /** Feedback the model gets: no tools, `compile_code` only, or `compile_code` and `simulate_code` with the plot. */
  val Conditions: List[String] = List("none", "compile", "full")

  /** The piece that stands for the task's goal, so the same pieces fit every task. */
  val Goal = "goal"

  /** No pieces: the original prompt, which asks only to simulate the program. */
  val DefaultPieces: List[String] = Nil

  // Same goal for the correct program and the broken ones: the prompt must not tell them apart
  val all: List[Task] =
    List("circle-control", "circle-bug-half", "circle-bug-maxby", "circle-bug-absolute").map { id =>
      Task(id, "circle-goal", Some(resource(s"tasks/$id.scala.txt").get), Target.Circle(0.6))
    }

  def byId(id: String): Option[Task] = all.find(_.id == id)

  def pieceExists(name: String): Boolean = name == Goal || resource(s"prompts/$name.txt").isDefined

  /** The text of `resources/prompts/<name>.txt`. */
  def piece(name: String): String = resource(s"prompts/$name.txt").get.trim

  private def resource(path: String): Option[String] =
    Option(getClass.getResourceAsStream(s"/$path")).map(stream =>
      Using.resource(Source.fromInputStream(stream))(_.mkString)
    )

}
