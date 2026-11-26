---
paths:
  - "mcp_server/src/main/scala/it/unibo/scafi/**"
  - "mcp_server/src/main/scala/it/unibo/alchemist/**"
  - "mcp_server/src/main/resources/swarmSimulation.yml"
---

# Swarm model and scenario

How they work: [Swarm model](../../docs/experiment.md#swarm-model) and [Simulation pipeline](../../docs/experiment.md#simulation-pipeline).

- The formations in `it.unibo.scafi` are inputs of the experiment, not code to improve: `simulation_agent/src/main/resources/tasks/circle-control.scala.txt` is an exact copy of `CircleFormation`, the `circle-bug-*` tasks are one-line mutants of it, and `SimulationProbe` uses the formations as references. Change one only on purpose, and update its copies and the probe in the same commit.
- They are on the classpath the agent's programs are compiled against, so a program can extend them.
- `swarmSimulation.yml` is the only source of the scenario parameters. A new parameter is a new entry in `variables`, with default and allowed values; the server reads them at startup, validates the `parameters` argument of `simulate_code` against them and lists them in the tool description. `{{ }}` and `{{EXPORT_DIR}}` stay template placeholders, because the CSV exporter puts every variable value in the file name.
- Running a simulation needs `-Dsun.java2d.opengl=false`, already set in `forkArgs` and in Docker.
