# AGENTS.md

Use [README.md](README.md) for setup and CLI usage, and [Experiment internals](docs/experiment.md) to navigate the implementation. Verify behavior against the source and its configuration. Update the relevant documentation when behavior changes. If the checkout root has a `CLAUDE.local.md`, read it too and follow it: it holds local instructions that are not versioned.

## Verification

- Use the repository's pinned `./mill` launcher.
- Run `./mill mill.scalalib.scalafmt/checkFormatAll` before committing Scala changes. See the [formatting instructions](README.md#formatting) for the formatter and editor setup.
- For changes to the simulator or oracle, run the `SimulationProbe` smoke test as configured in [CI](.github/workflows/ci.yml).
- Keep other Mill calls out of a worktree while the feedback matrix is running.

## Rules by path

Before editing or reviewing files, read the rules for every affected path. Claude Code loads them by itself.

- For `**/*.scala` and `build.mill`, read `.claude/rules/scala.md`.
- For `**/*.md`, read `.claude/rules/markdown.md`.
- For `CLAUDE.md`, `AGENTS.md`, `**/.claude/**`, `.agents/**` and `docs/agentic-development.md`, read `.claude/rules/agent-instructions.md`.
- For `FormationOracle.scala`, `Evaluate.scala` and `mcp_server/src/test/**`, read `.claude/rules/oracle.md`.
- For `mcp_server/src/main/scala/it/unibo/{scafi,alchemist}/**` and `swarmSimulation.yml`, read `.claude/rules/swarm-model.md`.
- For files under `mcp_server/`, `simulation_agent/` or `scripts/`, read the rule in that folder's `.claude/rules/`, State included: `mcp_server/.claude/rules/mcp-server.md`, `simulation_agent/.claude/rules/simulation-agent.md`, `scripts/.claude/rules/scripts.md`.

## Workflow

- Use `scripts/new-worktree.sh <type>/<task>` to create a separate worktree from `origin/master`. Keep each parallel session in its own worktree and avoid `git stash`, which is shared across worktrees.
- Target `master` on the `g-mainardi` fork for PRs. Treat `upstream` as read-only. Base dependent PRs on the branches they build on.
- Use Conventional Commits. Run `scripts/ff-merge.sh [branch]` from the branch worktree after the `success` check passes to merge while preserving commit signatures.
- Use `scripts/remove-worktree.sh <type>/<task>` to clean up a merged worktree.
- Record formatting-only commits in `.git-blame-ignore-revs` using the SHA they get on `master`.
- Report bugs and technical issues through GitHub Issues.

## Development setup

- [Agentic development](docs/agentic-development.md) describes how this repository is developed with coding agents: instructions, worktrees, stacked PRs, hooks and CI. Update it when you change any of them, and add to its lessons any mistake that led to a new rule.
