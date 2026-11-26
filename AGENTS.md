# AGENTS.md

Use [README.md](README.md) for setup and CLI usage, and [Experiment internals](docs/experiment.md) to navigate the implementation. Verify behavior against the source and its configuration. Update the relevant documentation when behavior changes.

## Verification

- Use the repository's pinned `./mill` launcher.
- Run `./mill mill.scalalib.scalafmt/checkFormatAll` before committing Scala changes. See the [formatting instructions](README.md#formatting) for the formatter and editor setup.
- For changes to the simulator or oracle, run the `SimulationProbe` smoke test as configured in [CI](.github/workflows/ci.yml).
- Keep other Mill calls out of a worktree while the feedback matrix is running.

## Workflow

- Use `scripts/new-worktree.sh <type>/<task>` to create a separate worktree from `origin/master`. Keep each parallel session in its own worktree and avoid `git stash`, which is shared across worktrees.
- Target `master` on the `g-mainardi` fork for PRs. Treat `upstream` as read-only. Base dependent PRs on the branches they build on.
- Use Conventional Commits. Run `scripts/ff-merge.sh [branch]` from the branch worktree after the `success` check passes to merge while preserving commit signatures.
- Use `scripts/remove-worktree.sh <type>/<task>` to clean up a merged worktree.
- Record formatting-only commits in `.git-blame-ignore-revs` using the SHA they get on `master`.
- Report bugs and technical issues through GitHub Issues.
