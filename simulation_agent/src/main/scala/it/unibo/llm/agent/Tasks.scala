package it.unibo.llm.agent

import scala.io.Source
import scala.util.Using

/** What the final formation should look like, for checking the agent's result. */
enum Target {

  case Circle(radius: Double)

}

/** A task for the agent: the prompt piece with its goal, the program it starts from (in
  * `resources/tasks/<id>.scala.txt`) and the target formation.
  */
final case class Task(id: String, goal: String, startingProgram: Option[String], target: Target) {

  /** The prompt sent to the model: goal, the given pieces, then the starting program, one paragraph each. */
  def fullPrompt(pieces: List[String]): String =
    ((goal :: pieces).map(Tasks.piece) ::: startingProgram.toList).mkString("\n\n")

}

object Tasks {

  /** Pieces added to every task unless the command line names others: the prompt before tasks had pieces. */
  val DefaultPieces: List[String] = List("scala3", "parameters")

  // Same goal for the correct program and the broken ones: the prompt must not tell them apart
  val all: List[Task] =
    List("circle-control", "circle-bug-half", "circle-bug-maxby", "circle-bug-absolute").map { id =>
      Task(id, "circle-goal", Some(resource(s"tasks/$id.scala.txt").get), Target.Circle(0.6))
    }

  def byId(id: String): Option[Task] = all.find(_.id == id)

  def pieceExists(name: String): Boolean = resource(s"prompts/$name.txt").isDefined

  /** The text of `resources/prompts/<name>.txt`. */
  def piece(name: String): String = resource(s"prompts/$name.txt").get.trim

  private def resource(path: String): Option[String] =
    Option(getClass.getResourceAsStream(s"/$path")).map(stream =>
      Using.resource(Source.fromInputStream(stream))(_.mkString)
    )

}
