package it.unibo.llm.exp

import org.junit.Test

import scala.collection.mutable

class MatrixTest {

  // A run that succeeds is started once, a failed one only in the passes after it, and an empty pass starts nothing
  @Test def laterPassesRetryOnlyMissingRuns(): Unit = {
    val started = mutable.ListBuffer.empty[(Int, String)]
    val done    = mutable.Set.empty[String]
    Matrix.passes(() => List("a", "b", "c").filterNot(done)) { (pass, todo) =>
      started ++= todo.map(pass -> _)
      done    ++= todo.filter(run => run != "c" || pass > 1)
    }
    assert(started.toList == List(1 -> "a", 1 -> "b", 1 -> "c", 2 -> "c"), started)
  }

}
