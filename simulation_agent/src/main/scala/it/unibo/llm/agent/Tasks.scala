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

  // Same prompt for the correct program and the broken ones: the prompt must not tell them apart
  private val CirclePrompt =
    """This ScaFi program should arrange the robot swarm in a circle around the leader. The goal, with the default
      |scenario parameters:
      |- every follower ends on a circle of radius 0.6 centred on the leader (the leader starts at (1, 0) and does not
      |  move);
      |- the followers are evenly spaced along the circle;
      |- the robots do not collide: they keep about `collisionArea` (0.2) apart.
      |Simulate the program with the default parameters and compare the last snapshot of the plot with the goal (each
      |snapshot has its own axis scale: read the numbers on the axes). If they differ, change the program and simulate
      |again, until the plot matches the goal. Meet the goal by changing the code, not the scenario `parameters`.
      |Finally, report the final program in full and say what you changed and why, or that nothing needed changing.""".stripMargin

  val all: List[Task] =
    List("circle-control", "circle-bug-half", "circle-bug-maxby", "circle-bug-absolute").map { id =>
      Task(id, CirclePrompt, Some(program(id)), Target.Circle(0.6))
    }

  def byId(id: String): Option[Task] = all.find(_.id == id)

  private def program(id: String): String =
    Using.resource(Source.fromResource(s"tasks/$id.scala.txt"))(_.mkString)

}
