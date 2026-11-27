# Scripts

How the matrix works: [Feedback comparison](../../../docs/experiment.md#feedback-comparison). The worktree and merge scripts are part of the [agentic development](../../../docs/agentic-development.md) setup.

- `matrix.sh` needs the MCP server up and the `.env` loaded. While it runs, no other `./mill` command may run in the same worktree: concurrent Mill clients once ran an agent twice. Don't commit or edit files there either: the records take the commit from the worktree. Run it again to resume an interrupted matrix: it skips the runs already there.
- A matrix writes its records under `runs/`, which git ignores and `remove-worktree.sh` deletes with the worktree: copy a finished matrix out of the worktree before removing it.
- `new-worktree.sh` copies `.env` and `CLAUDE.local.md` into the new worktree. `ff-merge.sh` merges by pushing the branch head onto its base as a fast-forward, which keeps the commit signatures: never merge with GitHub's buttons.

## State

`matrix.sh` (Bash) runs the four cells `base-none`, `base-compile`, `base-full` and `bare-full` on the circle tasks, with tasks and cells fixed in the script, and `summary.py` (Python) writes `summary.csv`. Missing: a Mill command with tasks and cells as arguments, and the summary in Scala.
