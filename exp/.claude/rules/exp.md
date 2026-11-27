# Feedback matrix

See [Feedback comparison](../../../docs/experiment.md#feedback-comparison) for scheduling, records and retries, and [Experiments](../../../docs/experiments/README.md) for the report template.

- Keep each run in its own agent process. The evaluator reads only completed records. Preserve filenames, CSV columns and compatibility with older records when changing the matrix.
- Keep other Mill calls and code changes out of a running matrix's worktree. Records take their revision from the working tree. Use the server jar, Docker or a separate worktree for the MCP server.
- Run `./mill exp.test` for the offline report checks. Compare changes to the summary against an existing matrix in a copy of its directory.

## State

`exp.matrix` selects tasks and cells, resumes completed runs, evaluates records and writes the experiment report. `exp.summary` rebuilds the CSV offline.
