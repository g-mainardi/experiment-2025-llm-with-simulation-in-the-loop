---
paths:
  - "mcp_server/src/main/scala/it/unibo/llm/mcp/server/FormationOracle.scala"
  - "mcp_server/src/main/scala/it/unibo/llm/mcp/server/Evaluate.scala"
  - "mcp_server/src/test/**"
---

# Oracle and offline evaluation

How they work: [Offline evaluation](../../docs/experiment.md#offline-evaluation).

- The oracle is the acceptance criterion of the experiment, not a feedback: its verdicts and metrics never reach the agent, neither in tool results nor in prompts.
- Every judged record keeps the `criterion` it was judged with, and `Evaluate` reuses an evaluation only when the criterion is the same. A change to a threshold, a metric or the window it is measured on must change the `criterion` as well, or old verdicts are reused silently.
- A new or changed threshold comes with evidence: the reference formations pass on seeds 0-9 and the negatives (broken copies, other shapes, `CircleFormation` against every slot shape) fail, with the margins written in the PR.
- After a change, re-evaluate a copy of earlier records with `--force` and report how many verdicts changed; never overwrite the only copy of a matrix.
- Run `SimulationProbe` (the command is in CI) after every change here; it also checks that the probe's mutants still fail.
- Compilations in `Evaluate` stay serialized: each loads the whole classpath, and parallel ones ran out of memory.
