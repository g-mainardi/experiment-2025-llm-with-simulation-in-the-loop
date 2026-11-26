# Agentic development

This repository is developed mostly through coding agents (Claude Code and Codex) driven by one developer. This page describes the setup around them: where their instructions come from, how work is split and merged, and which checks keep their output consistent. Update it when any of these changes.

## Instructions

- [`AGENTS.md`](../AGENTS.md) holds the instructions every agent follows. Codex reads it directly and `CLAUDE.md` imports it (`@AGENTS.md`), so both tools share one source.
- It stays short: how the code works is in [Experiment internals](experiment.md), setup and CLI usage in the [README](../README.md). Agents read those on demand instead of loading them in every session.
- `CLAUDE.local.md` holds personal instructions for a checkout. It is not versioned (excluded through `.git/info/exclude`) and `scripts/new-worktree.sh` copies it into new worktrees.

## From plan to pull request

- A larger change is planned first as a sequence of steps, each one branch and one pull request.
- Each step runs in its own worktree (`scripts/new-worktree.sh <type>/<task>`), with one agent session per worktree, so parallel sessions never share a working tree or `git stash`. The step's spec sits in an unversioned `plan.md` at the worktree root.
- Steps that build on each other become stacked pull requests: each targets the branch below it and moves to `master` when that one is merged.
- `scripts/ff-merge.sh` merges a pull request by fast-forwarding `master` onto it, which keeps the commit signatures that GitHub's rebase merge drops. `scripts/remove-worktree.sh` removes the worktree and the branch.

## Checks

- Git hooks, enabled by any `./mill` run (see [Git hooks](../README.md#git-hooks)): Conventional Commits for every message, scalafmt before a commit that touches Scala, and no deletion of a branch that an open pull request uses.
- CI ([`ci.yml`](../.github/workflows/ci.yml)): formatting, compilation of all modules, the `SimulationProbe` smoke test, the Docker build and the commit messages, gathered in the `success` check that `master` requires.
- Renovate ([`renovate.json`](../renovate.json)) opens the dependency updates, including the versions in `build.mill`.

## Lessons

Mistakes made by or with the agents, and the rule each one led to.

- Concurrent `./mill` clients in one worktree once made Mill run an agent twice in one invocation. The feedback matrix now builds with Mill once and runs the agent and the evaluator from the classpath, and `AGENTS.md` keeps other Mill calls out of a worktree while the matrix runs.
- Without explicit instructions, generated Scala drifts toward an imperative style (`while` loops, `var`, nested `if`/`else`) even next to functional code.
