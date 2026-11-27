# Experiment internals

## Overview

Experiment where an LLM acts as "programmer in the loop": it writes ScaFi aggregate programs (robot swarm formations) and validates them by compiling and simulating them in Alchemist through an MCP server.

## Running the experiment

Build tool is Mill (`./mill`, version pinned in `.mill-version`). Scala 3.10.0, the version the Alchemist ScaFi incarnation is built with (it is Scala 3 only since Alchemist 45). There are no tests: `mcp_server.test` only holds the dev-only `SimulationProbe` (`./mill mcp_server.test.runMain it.unibo.llm.mcp.server.SimulationProbe <abs path to CircleFormation.scala>`), which checks that `simulate_code` runs the submitted code (also with `class` in comments and strings and a redefined `ShapeFormation`), that `FormationOracle` accepts the given program and rejects the three `circle-bug-*` mutants, and that `LineFormation`, `SquareFormation`, `VFormation` (the files next to the given one) and a rotated `VFormation` (`+π/4`) each pass their own slot shape and no other while the given `CircleFormation` fails them all, on one seed. It exits with 1 when a check fails, and CI runs it as a smoke test.

```bash
./mill mcp_server.compile                 # compile a module
./mill mcp_server.run                     # start MCP server (ServerMain, Jetty on :8080, endpoint /mcp)
./mill mcp_server.assembly                # fat jar -> out/mcp_server/assembly.dest/out.jar
java -jar out/mcp_server/assembly.dest/out.jar
docker compose up                         # server via server.Dockerfile

./mill simulation_agent.run gemini-3.5-flash-lite circle-control [--pieces=a,b] [--condition=full] [--max-round-trips=20]   # Scala agent; server must already be running
./mill mcp_server.runMain it.unibo.llm.mcp.server.Evaluate "$PWD/runs" [--force] [--threads=n]   # judge the final programs of the run records
scripts/matrix.sh [runs/matrix-<date>] [k] [jobs]   # feedback comparison: tasks x cells x k runs in parallel, Evaluate, summary.csv; server running
```

- Model name starting with `gemini` uses Google AI (needs `GOOGLE_API_KEY`, kept in `.env`); anything else goes to Ollama at `localhost:11434`. Mill doesn't load `.env`: run `set -a; . ./.env; set +a` first, or Gemini answers "Permission denied on the service".
- Gemini runs use the most recent Flash Lite (`gemini-3.5-flash-lite` as of 2026-11; check the models list of the API for a newer one), never Flash or Pro: the experiment is whether the smallest model works.
- Agent logs (logback, `simulation_agent/src/main/resources/logback.xml`): the terminal shows INFO, i.e. each tool call with its arguments, the tool result and the final answer; `/tmp/simulation_agent.log` (or `$AGENT_LOG`) gets everything at DEBUG, including the raw MCP and model HTTP traffic, and is overwritten at each run.
- Agent tasks (`simulation_agent/.../Tasks.scala`): id, goal, starting program and target formation. `circle-control` starts from the correct `CircleFormation`, the `circle-bug-*` tasks from copies with one bug each and the same goal, which does not reveal the bug. Starting programs are `simulation_agent/src/main/resources/tasks/<id>.scala.txt` (the suffix keeps IDE and compiler from treating it as a source). An unknown task, piece or option prints an error with the usage.
- The prompt is made of pieces, one paragraph each: the pieces named on the command line, in their order (`--pieces`, comma separated, e.g. `--pieces=goal,scala3,parameters,classpath,api,simulate-first,stop`; `goal` stands for the task's goal, e.g. `circle-goal`), then the line `Run the simulation of this:` (`Here is the program:` without `full`) and the starting program. The default is no pieces, i.e. the original prompt of the upstream repository, without goal or hints. Pieces are `simulation_agent/src/main/resources/prompts/<name>.txt`; a new piece is just a new file.
- The goal says only what the formation must look like; how to check it is a procedure piece per condition (`procedure-full`, `procedure-compile`, `procedure-none`), and `parameters`, `simulate-first` and `stop` talk about `simulate_code`, so they fit only `full`. The full information base is `goal,procedure-full,scala3,parameters,classpath,api,simulate-first,stop` with `full`, `goal,procedure-<c>,scala3,classpath,api` otherwise.
- `--condition` sets the feedback, i.e. the tools the model gets: `full` (default) `compile_code` and `simulate_code`, `compile` only `compile_code` (`McpToolProvider.filterToolNames`), `none` no tools and no MCP connection, so the model answers in one request.
- Each run has a budget of `--max-round-trips` rounds of tool calls (default 20, i.e. at most 21 model requests without retries): a run that is still calling tools after that stops without a final answer. Failed compilations and simulations reach the model as tool errors, as do wrong tool arguments; any other tool failure (MCP server down) ends the run.

## Run records

Each run writes a flat JSON record to `runs/<yyyyMMdd-HHmmss>-<task>-<model>.json` (ignored by git; `--record=path` elsewhere), also when it fails ([`RunRecord.scala`](../simulation_agent/src/main/scala/it/unibo/llm/agent/RunRecord.scala)). The exit code is 1 for `error`, 0 otherwise. Fields, in the order of the record:

- `model`: the model name given on the command line.
- `temperature`: null for Gemini, which ignores it.
- `maxRoundTrips`: the round-trip budget of the run.
- `task`: the task id.
- `target`: the formation the task asks for: `{"shape": "circle", "radius": 0.6}`, or a slot shape `{"shape": "line", "spacing": 0.4}`, likewise `square` and `v`.
- `pieces`: the prompt pieces, `[]` for the default.
- `condition`: `none`, `compile` or `full`.
- `startedAt`: ISO-8601 with offset.
- `commit`: the commit checked out in the worktree when the record is written (`git rev-parse HEAD`); null outside a git checkout, missing in older records.
- `dirty`: whether the worktree had changes git doesn't ignore (`git status --porcelain`); null and missing like `commit`.
- `durationSeconds`: the time from the start of the run to the record.
- `outcome`: `answered`, `limit` when the round trips ran out, or `error`.
- `error`: the message, else null.
- `compileCalls`: the `compile_code` calls.
- `compileFailures`: the `compile_code` calls whose tool returned an error.
- `simulateCalls`: the `simulate_code` calls.
- `simulateFailures`: the `simulate_code` calls whose tool returned an error.
- `simulateParameters`: the `parameters` argument of each `simulate_code` call, null when absent: shows whether the agent changed the scenario instead of the program.
- `finalProgram`: the `program` argument of the last `simulate_code`, or of the last `compile_code` if it never simulated, else null. With `none`, the last code block tagged `scala` or untagged in the answer that defines a class, else the last such block: the model often repeats the changed lines in a block after the full program.
- `answer`: the final answer, null unless `answered`.
- `inputTokens`: the input tokens of the answered requests.
- `outputTokens`: the output tokens of the answered requests.
- `modelRequests`: requests sent to the model, retries included: the unit of the quota.
- `retries`: how many of those requests were retries.

## Offline evaluation

[`Evaluate`](../mcp_server/src/main/scala/it/unibo/llm/mcp/server/Evaluate.scala) runs offline with zero model requests and adds an `evaluation` object to each record that has none (`--force`: to all, e.g. after a threshold change), keeping the other fields. The agent never sees these numbers: the oracle is the acceptance criterion, not a feedback.

It simulates `finalProgram` with `FormationOracle`, ignoring the agent's `simulateParameters`, with the YAML defaults except:

- `radius` for a circle: the record's `target`, else the YAML default with a warning;
- `jitter` 0.1 and `endTime` 6000;
- seeds 0-2. They were 0-9 until 2026-11-13: the 13 records judged then had the same verdict on every seed, and three take a third of the time, at the cost of letting through a borderline program that a rarer seed would fail.

The simulations run in parallel on `--threads` threads (default: half the logical cores, as fast as all of them on a 20-thread laptop), and records with the same `finalProgram` and target (shape and radius or spacing) are simulated once; without `--force` a program already judged in the dir is not simulated again. The results are identical to a sequential evaluation (checked on the oracle branch records). Compilations are serialized, because each loads the whole classpath and twenty at once ran out of memory.

A slot shape (`line`, `square`, `v`) is judged on its ideal follower positions relative to the leader, spacing apart, in closed form and up to 2D rotation around the leader:

- `line`: through the leader, with 7 and 8 followers on the two sides;
- `square`: a full 4×4 grid with the leader in a corner;
- `v`: the leader at the apex and 90° arms of 7 and 8 followers.

The extra robot can be on either side and the leader in any corner, which together with 2D rotations also covers all reflections. For both circle and slot shapes `minDistance` is checked in the steady state only, because transient crossings on the way to the formation (down to 0.088 on the reference line) come from the soft repulsion of `ShapeFormation`. Any other shape gets `unsupported target shape`.

Fields of `evaluation`:

- `passed`: every seed passed.
- `reason`: null, or why the program could not be judged (no final program, compilation or simulation error, unsupported shape); then `passed` is false.
- `radius`: the circle radius; a slot shape has `spacing` instead.
- `commit`: the commit of the code that judged the record, read like the record's own. It doesn't stop an evaluation from being reused.
- `dirty`: whether that worktree had changes, like the record's own.
- `criterion`: the thresholds used; an evaluation is reused only with the same criterion.
  - `maxRmse`: circle only.
  - `maxAngularGap`: circle only.
  - `maxHausdorff`: slot shapes only, 0.5 × spacing.
  - `minDistance`: the smallest distance allowed between two robots.
  - `minDistanceWindow`: `"steadyState"`, distinguishing evaluations that measure `minDistance` in the steady state from earlier whole-run ones.
  - `steadyState`: the time units at the end of the run where the worst values are taken.
  - `endTime`: the length of each simulation.
  - `jitter`: the jitter of the initial grid.
  - `seeds`: the number of seeds; missing in the records judged on ten.
- `seeds`: one object per seed.
  - `seed`: the seed of this simulation.
  - `passed`: every threshold held on this seed.
  - `meanRadius`: circle only, at the end.
  - `rmse`: circle only, worst in the last `steadyState` time units.
  - `angularGap`: circle only, worst in the last `steadyState` time units.
  - `hausdorff`: slot shapes only, worst Hausdorff distance between followers and slots, nearest variant and rotation, in the steady state.
  - `minDistance`: worst in the steady state.
  - `convergence`: first time from which the RMSE stays within `maxRmse` (for a slot shape, the Hausdorff distance within `maxHausdorff`), or null.

## Feedback comparison

[`scripts/matrix.sh`](../scripts/matrix.sh) accepts `[dir] [k] [jobs]` and runs the feedback comparison on Flash Lite with `--max-round-trips=20`. A finished matrix is published in `docs/experiments/` with a report, as described in [Experiments](experiments/README.md).

- **Cells**: every task in four cells, `<prompt>-<condition>`: `base-none`, `base-compile`, `base-full` (the full information base for that condition) and `bare-full` (the bare original prompt, no pieces), k times each (default 10).
- **Parallel runs**: `jobs` runs at a time (default 4: a run mostly waits for the model), repetitions outermost so an interrupted matrix stays balanced.
- **Build**: mill only builds, at the start. Agent and `Evaluate` run from the launcher and the classpath, because concurrent mill clients in one worktree once made mill run an agent twice in one invocation, so don't run other `./mill` commands in the worktree during a matrix either. With `./mill mcp_server.run` up, mill can't recompile `mcp_server` nor format.
- **Files**: each run writes its record (`--record`) to `pending/` and, when it ends, the script moves it to `<cell>--<task>--rNN.json`, with the terminal output and the debug log (`AGENT_LOG`) in `logs/`. Rerun the script to resume: it skips the names already there.
- **Errors**: a run that ends in `error` (infrastructure, not the model) goes to `errors/`, out of `Evaluate`'s reach, and is run again in a second and third pass.
- **Evaluation**: while the runs go on, `Evaluate` judges the records already there, in a loop on 4 threads. When they end, that round is stopped and a last one runs on all threads (`Evaluate` writes each record aside and moves it, so stopping it never truncates one).
- **Summary**: then `scripts/summary.py <dir>` writes `summary.csv` per task and cell (plus `all`): success from `evaluation.passed`, outcomes, mean round trips, requests, tool calls, tokens and time, totals, and for `base-none` the Olausson baseline `1 - (1 - p)^N`, N the mean requests of `base-full` on the same task. `durationSeconds` of parallel runs includes some contention on the server.

## Architecture

[`build.mill`](../build.mill) defines two top-level modules.

- **`mcp_server`**: MCP server exposing two tools. `ServerMain` → `ScafiMcpServer`, which uses the plain MCP Java SDK 2.x (Jackson 3, mapper from `McpJsonDefaults`) with a Streamable HTTP servlet transport on Jetty. Tool input schemas are JSON files in `resources/schemas/`. `Main.scala` runs the scenario with `CircleFormation` in the Alchemist GUI, slowed down, for debugging. `FormationOracle` holds the circle and slot shape metrics and the acceptance thresholds used by `Evaluate` and `SimulationProbe`, on positions from `ScafiTestUtils.simulate` (the `simulate_code` pipeline without the plot).
- **`simulation_agent`**: Scala agent on LangChain4j 1.23: an `AiServices` assistant with the server's tools from `McpToolProvider`, which runs the tool loop. No dependency on `mcp_server`. Ollama models can't receive images in tool results (LangChain4j raises `UnsupportedFeatureException`), Gemini can.

The agent and server communicate over Streamable HTTP at `/mcp`. The agent uses `StreamableHttpMcpTransport` and the server uses `HttpServletStreamableServerTransportProvider`. The legacy HTTP SSE transport (`/mcp/sse` + `/mcp/message`) is deprecated by the MCP spec and no longer in recent LangChain4j releases.

### Simulation pipeline

[`ScafiTestUtils.scala`](../mcp_server/src/main/scala/it/unibo/llm/mcp/server/utils/ScafiTestUtils.scala) implements the pipeline. [`swarmSimulation.yml`](../mcp_server/src/main/resources/swarmSimulation.yml) defines the scenario.

1. **Compile**: `ScafiTestUtils.compile` runs the Scala 3 compiler in process (`dotty.tools.dotc.Driver`, `-usejavacp`, output to a temp dir), so submitted code is Scala 3 and compiles against the server's own classpath, including the formation classes in `it.unibo.scafi`. Its reporter returns `<SEVERITY>: <message> at line <n>`, the text the agent reads: dotc lines are 0-based and get +1, and the "N errors found" summary is dropped.
2. **Load**: package clauses and class name are extracted by regex from the source with comments and literals blanked out; the class is the first concrete one that extends `ShapeFormation`, else the last concrete one. Each simulation appends a unique chained package clause (`; package runN`, same line so compiler line numbers are unchanged) and points Alchemist's `ResourceLoader` (`org.kaikikm.threadresloader`, the per-thread loader `RunScafiProgram` uses to resolve the class name) at the output dir with a fresh loader (`setURLs`), closed and reset (`setDefault`) when the simulation ends, so classes from earlier simulations don't pile up. The loader delegates to the server classpath first, so without the unique package the class compiled into the server (e.g. `it.unibo.scafi.CircleFormation`) would be simulated instead of the submitted code.
3. **Simulate**: `resources/swarmSimulation.yml` is the only source of the scenario parameters, declared as Alchemist `variables` (grid, jitter, range, speed, seed, `endTime`, the molecules programs `sense`) with default and allowed values; the terminator and the export interval derive from them. The optional `parameters` tool argument overrides them through `Loader.getWith`; the server validates names and values first (`getWith` only checks names), and the tool description lists the variables, read from the YAML at startup. `{{ }}` (program class) and `{{EXPORT_DIR}}` stay template placeholders, not variables, because the CSV exporter puts every variable value in the file name. The `timeout` argument stops the simulation with `terminate()` from a timer thread (`run()` must stay on the caller thread, which owns the `ResourceLoader`); the partial plot comes back with a note.
4. **Plot**: the CSV exporter output (the only `.csv` in the export dir, named after the variable values; space-delimited, time + node positions) is parsed and plotted with nspl into a grid of snapshots. The PNG is returned base64-encoded as MCP `ImageContent`, so the agent LLM must be multimodal.

### Swarm model

- `it.unibo.scafi`: `BaseFormation` (ScaFi `AggregateProgram` + blocks) → `ShapeFormation` (leader election, local goal, collision avoidance) → concrete shapes that override `calculateSuggestion` (Circle, Line, Square, V, ...). Programs write an `Actuation` (`Rotation`/`Forward`/`NoOp`/`Stop`) to the `actuation` molecule.
- `it.unibo.alchemist.action`: custom Alchemist extensions. `DirectionalRobotProperty` (a `Capability`, listed under `capabilities` in the YAML) holds the node orientation, and `DifferentialRobotMovement` (an `AbstractMoveNode` built from the `NodeReaction` hosting it, inside a `GenericReaction`) reads the `actuation` molecule and moves the node. The YAML template wires both, plus the `leader`, `stabilityThreshold` and `collisionArea` molecules the programs `sense`.
- Runtime needs `-Dsun.java2d.opengl=false` (set in `forkArgs` and in Docker).
