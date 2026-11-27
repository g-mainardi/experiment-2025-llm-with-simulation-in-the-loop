package it.unibo.llm.exp

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SummaryTest {

  @Rule def temporary: TemporaryFolder = folder

  private val folder = new TemporaryFolder()

  @Test def judgedRunsAndAdditionalCosts(): Unit = {
    val dir    = folder.newFolder().toPath
    val record =
      """{"task":"circle-control","model":"test","maxRoundTrips":20,"pieces":[],"condition":"none","outcome":"limit","modelRequests":2,"retries":0,"compileCalls":0,"simulateCalls":0,"inputTokens":10,"outputTokens":3,"durationSeconds":2.5,"evaluation":{"passed":true},"dirty":true}"""
    Files.writeString(dir.resolve("bare-none--circle-control--r01.json"), record)
    List("errors", "pilot").foreach { group =>
      Files.createDirectory(dir.resolve(group))
      Files.writeString(dir.resolve(group).resolve("trial.json"), record)
    }
    val rows = Summary.write(dir)
    val csv  = Files.readString(dir.resolve("summary.csv"))
    assert(
      csv.linesIterator.drop(1).toList == List("circle-control", "all").map(task =>
        s"$task,bare-none,1,1,1,0,1,0,1,2,0,0,10,3,2,10,3,2.5,,"
      )
    )
    def report() = Report.write(dir, dir.toString, 1, 1, List("circle-control"), List("bare-none"), rows)
    report()
    val readme  = dir.resolve("README.md")
    val content = Files.readString(readme)
    assert(content.contains("20.7%–100.0%"))
    assert(content.contains("| Total | 3 | 6 | 30 | 9 | 7.5 |"))
    assert(content.contains("unrecorded") && content.contains("dirty worktree"))
    Files.writeString(readme, "Edited after the run")
    report()
    assert(Files.readString(readme) == "Edited after the run")
  }

  @Test def multiTaskOlaussonAggregateAndCleanWarnings(): Unit = {
    val dir                                                                                             = folder.newFolder().toPath
    def record(task: String, condition: String, passed: Boolean, requests: Int, commit: String): String =
      s"""{"task":"$task","model":"gemini-3.5-flash-lite","maxRoundTrips":20,"pieces":["goal"],"condition":"$condition","commit":"$commit","dirty":false,"outcome":"answered","modelRequests":$requests,"retries":0,"compileCalls":0,"simulateCalls":0,"inputTokens":100,"outputTokens":20,"durationSeconds":1.5,"evaluation":{"passed":$passed,"commit":"$commit","dirty":false,"criterion":{"seeds":3}}}"""

    Files.writeString(
      dir.resolve("base-none--circle-control--r01.json"),
      record("circle-control", "none", true, 1, "aaa")
    )
    Files.writeString(
      dir.resolve("base-none--circle-control--r02.json"),
      record("circle-control", "none", false, 1, "aaa")
    )
    Files.writeString(
      dir.resolve("base-full--circle-control--r01.json"),
      record("circle-control", "full", true, 3, "aaa")
    )
    Files.writeString(
      dir.resolve("base-none--circle-bug-half--r01.json"),
      record("circle-bug-half", "none", false, 1, "bbb")
    )
    Files.writeString(
      dir.resolve("base-full--circle-bug-half--r01.json"),
      record("circle-bug-half", "full", true, 2, "aaa")
    )

    val rows = Summary.write(dir)
    val csv  = Files.readString(dir.resolve("summary.csv")).linesIterator.toList
    // circle-control base-none has p = 0.5 and base-full has N = 3 => 1 - 0.5^3 = 0.875
    assert(csv.contains("circle-control,base-none,2,1,0.5,2,0,0,0,1,0,0,100,20,2,200,40,1.5,3,0.875"))
    assert(csv.contains("all,base-none,3,1,0.333,3,0,0,0,1,0,0,100,20,3,300,60,1.5,,"))

    Report.write(
      dir,
      "runs/test",
      1,
      2,
      List("circle-control", "circle-bug-half"),
      List("base-none", "base-full"),
      rows
    )
    val content = Files.readString(dir.resolve("README.md"))
    assert(content.contains("multiple commits"))
    assert(!content.contains("dirty worktree"))
    assert(content.contains("| all | base-none | 1/3 | 33.3% |"))
    assert(content.contains("| all | base-full | 2/2 | 100.0% |"))
    assert(!content.contains("\n\n\n"))

    val cleanDir = folder.newFolder().toPath
    Files.writeString(
      cleanDir.resolve("base-none--circle-control--r01.json"),
      record("circle-control", "none", true, 1, "aaa")
    )
    val cleanRows = Summary.write(cleanDir)
    Report.write(cleanDir, "runs/clean", 1, 1, List("circle-control"), List("base-none"), cleanRows)
    val cleanContent = Files.readString(cleanDir.resolve("README.md"))
    assert(!cleanContent.contains("multiple commits") && !cleanContent.contains("dirty worktree"))
    assert(!cleanContent.contains("\n\n\n"))
  }

}
