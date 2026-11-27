# Scripts

The worktree and merge scripts are part of the [agentic development](../../../docs/agentic-development.md) setup.

- A matrix writes its records under `runs/`, which git ignores and `remove-worktree.sh` deletes with the worktree: publish a finished matrix in `docs/experiments/`, as described in [Experiments](../../../docs/experiments/README.md), before removing the worktree.
- `new-worktree.sh` copies `.env` and `CLAUDE.local.md` into the new worktree. `ff-merge.sh` merges by pushing the branch head onto its base as a fast-forward, which keeps the commit signatures: never merge with GitHub's buttons.

## State

The scripts create worktrees, fast-forward reviewed branches and remove merged worktrees.
