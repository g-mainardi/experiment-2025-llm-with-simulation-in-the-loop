# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Experiment where an LLM acts as "programmer in the loop": it writes ScaFi aggregate programs (robot swarm formations) and validates them by compiling and simulating them in Alchemist through an MCP server.

## Commands

Build tool is Mill (`./mill`, version pinned in `.mill-version`). Scala 3.10.0, the version the Alchemist ScaFi incarnation is built with (it is Scala 3 only since Alchemist 45). There are no tests: `mcp_server.test` only holds the dev-only `SimulationProbe` (`./mill mcp_server.test.runMain it.unibo.llm.mcp.server.SimulationProbe <abs path to CircleFormation.scala>`), which checks that `simulate_code` runs the submitted code and prints the circle metrics. It exits with 1 when the check fails, and CI runs it as a smoke test.

```bash
./mill mcp_server.compile                 # compile a module
./mill mcp_server.run                     # start MCP server (ServerMain, Jetty on :8080, endpoint /mcp)
./mill mcp_server.assembly                # fat jar -> out/mcp_server/assembly.dest/out.jar
java -jar out/mcp_server/assembly.dest/out.jar
docker compose up                         # server via server.Dockerfile

./mill simulation_agent.run "gemini-3.6-flash"   # Scala agent; server must already be running
./mill simulation_agent_py.run                   # Python agent (LangChain, Ollama qwen3-vl:2b)
```

- Model name starting with `gemini` uses Google AI (needs `GOOGLE_API_KEY`, kept in `.env`); anything else goes to Ollama at `localhost:11434`.
- Agent logs (logback, `simulation_agent/src/main/resources/logback.xml`): the terminal shows INFO, i.e. each tool call with its arguments, the tool result and the final answer; `/tmp/simulation_agent.log` gets everything at DEBUG, including the raw MCP and model HTTP traffic, and is overwritten at each run.
- The agent's task prompt is hardcoded in `simulation_agent/.../Main.scala`, not passed as an argument.
- Formatting: scalafmt (`.scalafmt.conf`, max 120 cols, Scala 3 dialect). `./mill mill.scalalib.scalafmt/reformatAll` formats, `checkFormatAll` checks.

## Architecture

Three Mill modules (`build.mill`):

- **`mcp_server`**: MCP server exposing two tools. `ServerMain` → `ScafiMcpServer`, which uses the plain MCP Java SDK 2.x (Jackson 3, mapper from `McpJsonDefaults`) with a Streamable HTTP servlet transport on Jetty. Tool input schemas are JSON files in `resources/schemas/`. `Main.scala` runs the scenario with `CircleFormation` in the Alchemist GUI, slowed down, for debugging.
- **`simulation_agent`**: Scala agent on LangChain4j 1.23: an `AiServices` assistant with the server's tools from `McpToolProvider`, which runs the tool loop. No dependency on `mcp_server`. Ollama models can't receive images in tool results (LangChain4j raises `UnsupportedFeatureException`), Gemini can.
- **`simulation_agent_py`**: Python equivalent using `langchain-mcp-adapters`.

Client and server transports have to match. All three use Streamable HTTP on the single endpoint `/mcp`: `HttpServletStreamableServerTransportProvider` on the server, `StreamableHttpMcpTransport` in the Scala agent, `streamable_http` in the Python one. The legacy HTTP SSE transport (`/mcp/sse` + `/mcp/message`) is deprecated by the MCP spec and no longer in recent LangChain4j releases.

### Simulation pipeline (`mcp_server/.../utils/ScafiTestUtils.scala`)

1. **Compile**: `ScafiTestUtils.compile` runs the Scala 3 compiler in process (`dotty.tools.dotc.Driver`, `-usejavacp`, output to a temp dir), so submitted code is Scala 3 and compiles against the server's own classpath, including the formation classes in `it.unibo.scafi`. Its reporter returns `<SEVERITY>: <message> at line <n>`, the text the agent reads: dotc lines are 0-based and get +1, and the "N errors found" summary is dropped.
2. **Load**: package clauses and class name are extracted by regex (first `class` in the source). Each simulation appends a unique chained package clause (`; package runN`, same line so compiler line numbers are unchanged) and points Alchemist's `ResourceLoader` (`org.kaikikm.threadresloader`, the per-thread loader `RunScafiProgram` uses to resolve the class name) at the output dir with a fresh loader (`setURLs`), closed and reset (`setDefault`) when the simulation ends, so classes from earlier simulations don't pile up. The loader delegates to the server classpath first, so without the unique package the class compiled into the server (e.g. `it.unibo.scafi.CircleFormation`) would be simulated instead of the submitted code.
3. **Simulate**: `resources/swarmSimulation.yml` is the only source of the scenario parameters, declared as Alchemist `variables` (grid, jitter, range, speed, seed, `endTime`, the molecules programs `sense`) with default and allowed values; the terminator and the export interval derive from them. The optional `parameters` tool argument overrides them through `Loader.getWith`; the server validates names and values first (`getWith` only checks names), and the tool description lists the variables, read from the YAML at startup. `{{ }}` (program class) and `{{EXPORT_DIR}}` stay template placeholders, not variables, because the CSV exporter puts every variable value in the file name. The `timeout` argument stops the simulation with `terminate()` from a timer thread (`run()` must stay on the caller thread, which owns the `ResourceLoader`); the partial plot comes back with a note.
4. **Plot**: the CSV exporter output (the only `.csv` in the export dir, named after the variable values; space-delimited, time + node positions) is parsed and plotted with nspl into a grid of snapshots. The PNG is returned base64-encoded as MCP `ImageContent`, so the agent LLM must be multimodal.

### Swarm model

- `it.unibo.scafi`: `BaseFormation` (ScaFi `AggregateProgram` + blocks) → `ShapeFormation` (leader election, local goal, collision avoidance) → concrete shapes that override `calculateSuggestion` (Circle, Line, Square, V, ...). Programs write an `Actuation` (`Rotation`/`Forward`/`NoOp`/`Stop`) to the `actuation` molecule.
- `it.unibo.alchemist.action`: custom Alchemist extensions. `DirectionalRobotProperty` (a `Capability`, listed under `capabilities` in the YAML) holds the node orientation, and `DifferentialRobotMovement` (an `AbstractMoveNode` built from the `NodeReaction` hosting it, inside a `GenericReaction`) reads the `actuation` molecule and moves the node. The YAML template wires both, plus the `leader`, `stabilityThreshold` and `collisionArea` molecules the programs `sense`.
- Runtime needs `-Dsun.java2d.opengl=false` (set in `forkArgs` and in Docker).

## Workflow

- `origin` (g-mainardi fork) is the reference repo, `upstream` (nicolasfara) is read-only. Run `gh` commands against the fork: PRs must target `g-mainardi/...:master`, not upstream.
- GitHub flow: short-lived branches from `master` (`feat/…`, `fix/…`, `ci/…`, `exp/…`), merged by PR with `scripts/ff-merge.sh [branch]`, not GitHub's merge button: GitHub's rebase-merge drops the commit signatures. The script rebases the branch on its base if needed (force-push, then wait for CI and run it again), checks that the PR is `CLEAN`, and fast-forwards the base onto it, so commits keep their SHA and signature and GitHub marks the PR merged. It then deletes the local branch and its `origin/` tracking ref; GitHub deletes the remote one (auto-delete on merge is on). A ruleset requires a PR and the `success` check on `master`, which this direct push satisfies. Dependent PRs go in a GitHub stack on the branch they build on, so they retarget to `master` once it's merged.
- Commit messages follow Conventional Commits, checked by `.githooks/commit-msg` locally and by CI on every PR commit. `.githooks/pre-commit` rejects unformatted Scala sources. Any `./mill` run sets `core.hooksPath` to `.githooks` and `blame.ignoreRevsFile` to `.git-blame-ignore-revs` (see `build.mill`).
- Formatting-only commits go in `.git-blame-ignore-revs`, with the SHA they get on `master` (a rebase by `ff-merge.sh` rewrites them).
- CI (`.github/workflows/ci.yml`): scalafmt check, compile all modules, `SimulationProbe`, Docker build, commit check, aggregated in the `success` job.
- Renovate (`renovate.json`) opens dependency PRs; `build.mill` and `.mill-version` are covered by regex managers.
- Bugs and technical issues go in GitHub Issues.
