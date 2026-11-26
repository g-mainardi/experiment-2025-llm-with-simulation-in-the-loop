# MCP server

How it works: [Architecture](../../../docs/experiment.md#architecture) and [Simulation pipeline](../../../docs/experiment.md#simulation-pipeline). The oracle has its own rule (`.claude/rules/oracle.md`), the formations and the scenario too (`.claude/rules/swarm-model.md`).

- Check with `./mill mcp_server.compile`, and after a change to the pipeline or the tools run `SimulationProbe` (the command is in CI): it fails when `simulate_code` stops running the submitted code.
- The tool descriptions and the input schemas in `resources/schemas/` are what the agent reads, so their wording is part of the experiment like the prompt pieces: say in the PR when a change rewords them.
- `simulate_code` must simulate the submitted code, never a class compiled into the server: keep the unique package clause per simulation and the fresh `ResourceLoader`. `run()` stays on the caller thread, which owns the loader; the timeout stops the simulation from a timer thread.
- Compiler messages reach the agent as `<SEVERITY>: <message> at line <n>`, with 1-based lines: keep that format.
- While `./mill mcp_server.run` is up, Mill can't recompile `mcp_server` or format: stop the server first.

## State

The server runs on Jetty (port 8080, endpoint `/mcp`, Streamable HTTP) with two tools: `compile_code`, and `simulate_code` with the scenario `parameters`, a `timeout` and the plot as a PNG. The offline oracle judges circles (radius RMSE, angular gap, minimum distance over the whole run) and `Evaluate` judges run records in parallel, simulating identical programs once. `SimulationProbe` runs in CI. Missing on `master`: the line, square and V targets with rotation-invariant judging, and a fix for the class name taken from the first `class` in the source (issue #23), both in review.
