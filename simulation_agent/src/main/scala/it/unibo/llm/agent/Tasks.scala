package it.unibo.llm.agent

import scala.io.Source
import scala.util.Using

/** What the final formation should look like, for checking the agent's result. */
enum Target {

  case Circle(radius: Double)

}

/** A task for the agent: the prompt, the program it starts from (in `resources/tasks/<id>.scala.txt`) and the goal. */
final case class Task(id: String, prompt: String, startingProgram: Option[String], target: Target) {

  /** The prompt sent to the model: task prompt and common preamble in one paragraph, then the starting program. */
  def fullPrompt: String = (s"$prompt ${Tasks.Preamble}" :: startingProgram.toList).mkString("\n", "\n\n", "")

}

object Tasks {

  /** Facts about the tools that hold for every task. */
  val Preamble: String =
    """The tools compile programs as Scala 3, so any code you write must be
      |Scala 3: Scala 2 procedure syntax (`def f() { ... }`) and symbol literals (`'foo`) do not compile.
      |`simulate_code` also accepts `parameters` to change the scenario (e.g. number of robots, radius); its
      |description lists the variables with their defaults and allowed values.""".stripMargin

  val all: List[Task] = List(
    Task(
      "circle-simulate",
      "Run the simulation of this ScaFi program.",
      Some(program("circle-simulate")),
      Target.Circle(0.6)
    )
  )

  def byId(id: String): Option[Task] = all.find(_.id == id)

  private def program(id: String): String =
    Using.resource(Source.fromResource(s"tasks/$id.scala.txt"))(_.mkString)

}
