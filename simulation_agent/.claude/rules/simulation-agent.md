# Simulation agent

How it works: [Running the experiment](../../../docs/experiment.md#running-the-experiment) and [Run records](../../../docs/experiment.md#run-records); arguments and options in the [README](../../../README.md#start-the-agent).

- Check with `./mill simulation_agent.compile`. A run needs the MCP server up (except with `--condition=none`) and the `.env` loaded first (`set -a; . ./.env; set +a`): Mill doesn't load it.
- Runs use only the most recent Gemini Flash Lite (`gemini-3.5-flash-lite` as of 2026-11), never Flash or Pro: the experiment asks whether the smallest model works. Every model request counts against the quota: report how many a trial used (`modelRequests` in the record).
- The prompt pieces in `resources/prompts/` are the information base of the experiment: rewording a piece changes the conditions of every result obtained with it. Prefer a new piece; otherwise say in the PR which results the change affects.
- `parameters`, `simulate-first`, `stop` and `procedure-full` talk about `simulate_code`, so they fit only `--condition=full`.
- A task is an entry in `Tasks.scala` with its starting program in `resources/tasks/<id>.scala.txt`. A task with a new kind of target needs a `Target` case and the matching field in `RunRecord`, in the form `Evaluate` reads.
- A new or changed field of `RunRecord` is documented in [Run records](../../../docs/experiment.md#run-records) in the same commit, and older records must stay readable by `Evaluate` and by the summary of the matrix.
- Ollama models can't receive images in tool results; Gemini can.

## State

Tasks `circle-control` and `circle-bug-half`, `circle-bug-maxby`, `circle-bug-absolute`, all with the circle goal. Prompt from pieces, feedback conditions `none`, `compile` and `full`, a limit on tool-call round trips, retries on Gemini rate limits and overload, one JSON record per run. Missing: targets other than the circle, and a condition with `simulate_code` only.
