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
    * the original prompt. Without pieces it is the original prompt of the professor's repo.
    */
  def fullPrompt(pieces: List[String]): String =
    (pieces.map(name => Tasks.piece(if (name == Tasks.Goal) goal else name)) :::
      startingProgram.toList.map(program => s"Run the simulation of this:\n$program")).mkString("\n\n")

}

object Tasks {

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
