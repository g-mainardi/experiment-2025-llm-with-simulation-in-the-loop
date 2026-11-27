---
paths:
  - "**/*.scala"
  - "build.mill"
---

# Scala

- Scala 3.10.0 everywhere, the version the Alchemist ScaFi incarnation is built with. The programs the agent submits are compiled as Scala 3 too, against the server's classpath.
- Formatting is scalafmt with `.scalafmt.conf` (Scala 3 dialect, 120 columns). Check with `./mill mill.scalalib.scalafmt/checkFormatAll`, fix with `./mill mill.scalalib.scalafmt/reformatAll`; the `pre-commit` hook rejects unformatted sources. A commit that only reformats goes in `.git-blame-ignore-revs` with the SHA it gets on `master`.
- `./mill __.compile` compiles every module, as CI does.

## Functional style

- Use `val` and immutable collections for new Scala code. Replace `var` accumulators and `while` loops with collection operations such as `map`, `filter` and `foldLeft`. Use tail recursion for state transitions without a direct collection operation.
- Express decisions as values. Use an `if` expression for a simple condition and pattern matching for distinct cases or nested decisions.
- Model absence with `Option` and failures the caller can handle with `Either`. Use `map` to transform a value, `flatMap` when the next step can also return `None` or `Left`, and `getOrElse` or `orElse` for fallbacks.
- Keep required mutation at API boundaries, for example when populating a Jackson JSON object or updating state owned by Alchemist. Keep the calculations that feed those effects pure.

### Examples

[`RunRecord.write`](../../simulation_agent/src/main/scala/it/unibo/llm/agent/RunRecord.scala) selects the last simulation call, falling back to the last compilation call.

```scala
// Before
val finalCall =
  if (simulates.nonEmpty) Some(simulates.last)
  else if (compiles.nonEmpty) Some(compiles.last)
  else None

// After
val finalCall = simulates.lastOption.orElse(compiles.lastOption)
```

[`ShapeFormation.computeRepulsionSum`](../../mcp_server/src/main/scala/it/unibo/scafi/ShapeFormation.scala) sums the calculated repulsion vectors. With those vectors already calculated, both versions add them in iteration order and return zero for an empty collection.

```scala
// Before
var sum = Point3D.Zero
val remaining = repulsionVectors.iterator
while (remaining.hasNext) {
  sum = sum + remaining.next()
}

// After
val sum = repulsionVectors.foldLeft(Point3D.Zero)(_ + _)
```

The [agent CLI](../../simulation_agent/src/main/scala/it/unibo/llm/agent/Main.scala) matches the expected input shape while extracting the model and task ID.

```scala
// Before
val (modelName, taskId) =
  if (positional.size == 2) (positional.head, positional(1))
  else usageError("Expected a model and a task id")

// After
val (modelName, taskId) = positional match {
  case List(model, task) => (model, task)
  case _                 => usageError("Expected a model and a task id")
}
```
