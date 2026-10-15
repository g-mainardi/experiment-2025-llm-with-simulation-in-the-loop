# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Experiment where an LLM acts as "programmer in the loop": it writes ScaFi aggregate programs (robot swarm formations) and validates them by compiling and simulating them in Alchemist through an MCP server.

## Commands

Build tool is Mill (`./mill`, version pinned in `.mill-version`). Scala 2.13.17. There are no tests: `mcp_server.test` only holds the dev-only `SimulationProbe` (`./mill mcp_server.test.runMain it.unibo.llm.mcp.server.SimulationProbe <abs path to CircleFormation.scala>`), which checks that `simulate_code` runs the submitted code and prints the circle metrics. It exits with 1 when the check fails, and CI runs it as a smoke test.

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
- The agent's task prompt is hardcoded in `simulation_agent/.../Main.scala`, not passed as an argument.
- Formatting: scalafmt (`.scalafmt.conf`, max 120 cols, Scala 3 dialect for Mill build files). `./mill mill.scalalib.scalafmt/reformatAll` formats, `checkFormatAll` checks.

## Architecture

Three Mill modules (`build.mill`):

- **`mcp_server`**: MCP server exposing two tools. `ServerMain` → `ScafiMcpServer`, which uses the plain MCP Java SDK with a Streamable HTTP servlet transport on Jetty. Tool input schemas are JSON files in `resources/schemas/`. `SpringMain` + `tool/ScafiTools.scala` + `application.properties` are an alternative Spring AI implementation of the same tools (different tool names: `compile_scafi`/`simulate_scafi` vs `compile_code`/`simulate_code`), not the default main class. `Main.scala` just runs `swarmSimulationTest.yml` directly, for debugging.
- **`simulation_agent`**: Scala LangGraph4j `AgentExecutor` + LangChain4j MCP client. Lists the server's tools and registers them on the agent. Depends on `mcp_server` (classpath only).
- **`simulation_agent_py`**: Python equivalent using `langchain-mcp-adapters`.

Client and server transports have to match. All three use Streamable HTTP on the single endpoint `/mcp`: `HttpServletStreamableServerTransportProvider` on the server, `StreamableHttpMcpTransport` in the Scala agent, `streamable_http` in the Python one. The legacy HTTP SSE transport (`/mcp/sse` + `/mcp/message`) is deprecated by the MCP spec and no longer in recent LangChain4j releases.

### Simulation pipeline (`mcp_server/.../utils/ScafiTestUtils.scala`)

This is the core logic behind both tool implementations:

1. **Compile**: in-process `scala.tools.nsc.Global` with a `StoreReporter`, output to a temp dir. `compileAndGetErrors` uses `usejavacp`. `simulateProgram` sets the classpath from `java.class.path`. Submitted code therefore compiles against the server's own classpath, including the formation classes in `it.unibo.scafi`.
2. **Load**: package clauses and class name are extracted by regex (first `class` in the source). Each simulation appends a unique chained package clause (`; package runN`, same line so compiler line numbers are unchanged) and points Alchemist's `ResourceLoader` (`org.kaikikm.threadresloader`, the per-thread loader `RunScafiProgram` uses to resolve the class name) at the output dir with a fresh loader (`setURLs`), closed and reset (`setDefault`) when the simulation ends, so classes from earlier simulations don't pile up. The loader delegates to the server classpath first, so without the unique package the class compiled into the server (e.g. `it.unibo.scafi.CircleFormation`) would be simulated instead of the submitted code.
3. **Simulate**: `resources/swarmSimulation.yml` is a template. `{{ }}` gets the fully qualified program class and `{{EXPORT_DIR}}` the CSV export path. It runs with an `AfterTime(3000)` terminator. The `timeout` tool argument is parsed but not currently enforced.
4. **Plot**: the CSV exporter output (`experiment.csv`, space-delimited, time + node positions) is parsed and plotted with nspl into a grid of snapshots. The PNG is returned base64-encoded as MCP `ImageContent`, so the agent LLM must be multimodal.

### Swarm model

- `it.unibo.scafi`: `BaseFormation` (ScaFi `AggregateProgram` + blocks) → `ShapeFormation` (leader election, local goal, collision avoidance) → concrete shapes that override `calculateSuggestion` (Circle, Line, Square, V, ...). Programs write an `Actuation` (`Rotation`/`Forward`/`NoOp`/`Stop`) to the `actuation` molecule.
- `it.unibo.alchemist.action`: custom Alchemist extensions. `DirectionalRobotProperty` holds the node orientation, and `DifferentialRobotMovement` reads the `actuation` molecule and moves the node. The YAML template wires both, plus the `leader`, `stabilityThreshold` and `collisionArea` molecules the programs `sense`.
- Runtime needs `-Dsun.java2d.opengl=false` (set in `forkArgs` and in Docker).

## Workflow

- `origin` (g-mainardi fork) is the reference repo, `upstream` (nicolasfara) is read-only. Run `gh` commands against the fork: PRs must target `g-mainardi/...:master`, not upstream.
- GitHub flow: short-lived branches from `master` (`feat/…`, `fix/…`, `ci/…`, `exp/…`), merged by PR with rebase-merge (keeps atomic commits) or squash-merge (one commit, subject = PR title). A ruleset blocks direct pushes to `master` and requires the `success` check.
- Commit messages follow Conventional Commits, checked by `.githooks/commit-msg` locally and by CI on every PR commit. `.githooks/pre-commit` rejects unformatted Scala sources. Any `./mill` run sets `core.hooksPath` to `.githooks` and `blame.ignoreRevsFile` to `.git-blame-ignore-revs` (see `build.mill`).
- Formatting-only commits go in `.git-blame-ignore-revs`, with the SHA they get on `master` (rebase-merge rewrites them).
- CI (`.github/workflows/ci.yml`): scalafmt check, compile all modules, `SimulationProbe`, Docker build, commit check, aggregated in the `success` job.
- Renovate (`renovate.json`) opens dependency PRs; `build.mill` and `.mill-version` are covered by regex managers.
- Bugs and technical issues go in GitHub Issues.
