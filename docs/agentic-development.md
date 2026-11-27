# Agentic development

This repository is developed mostly through coding agents (Claude Code and Codex) driven by one developer. This page describes the setup around them: where their instructions come from, how work is split and merged, and which checks keep their output consistent. Update it when any of these changes.

## Instructions

- [`AGENTS.md`](../AGENTS.md) holds the instructions every agent follows. Codex reads it directly and `CLAUDE.md` imports it (`@AGENTS.md`), so both tools share one source.
- It stays short: how the code works is in [Experiment internals](experiment.md), setup and CLI usage in the [README](../README.md). Agents read those on demand instead of loading them in every session.
- What holds only in part of the repository is a rule. Area rules sit in `.claude/rules/` with a `paths:` frontmatter (Scala, Markdown, the oracle, the swarm model, these instructions); each module (`mcp_server`, `simulation_agent`, `exp`, `scripts`) has its own in `<module>/.claude/rules/`, ending with a State section that says what is done and what is missing. Claude Code loads a rule when it touches a matching file; Codex follows the *Rules by path* list in `AGENTS.md`. Rules say what to do and check in their area and link to the internals instead of repeating them.
- `CLAUDE.local.md` holds personal instructions for a checkout. It is not versioned (excluded through `.git/info/exclude`) and `scripts/new-worktree.sh` copies it into new worktrees.

## From plan to pull request

- A larger change is planned first as a sequence of steps, each one branch and one pull request.
- Each step runs in its own worktree (`scripts/new-worktree.sh <type>/<task>`), with one agent session per worktree, so parallel sessions never share a working tree or `git stash`. The step's spec sits in an unversioned `plan.md` at the worktree root.
- Steps that build on each other become stacked pull requests: each targets the branch below it and moves to `master` when that one is merged.
- The maintainer merges pull requests. An agent can merge a specific pull request at the user's explicit request. After the `success` check passes, `scripts/ff-merge.sh` fast-forwards the base to the branch head and preserves commit signatures. `scripts/remove-worktree.sh` removes the merged worktree and branch.

## Checks

- Git hooks, enabled by any `./mill` run (see [Git hooks](../README.md#git-hooks)): Conventional Commits for every message, scalafmt before a commit that touches Scala, and no deletion of a branch that an open pull request uses.
- CI ([`ci.yml`](../.github/workflows/ci.yml)): formatting, compilation of all modules, the offline matrix report test, the `SimulationProbe` smoke test, a program judged by `Evaluate` from the server jar, the Docker build and the commit messages, gathered in the `success` check that `master` requires.
- Renovate ([`renovate.json`](../renovate.json)) opens the dependency updates, including the versions in `build.mill`.
- To track adoption of the [Scala style rule](../.claude/rules/scala.md#functional-style), count new uses of `while` and `var` in PR diffs. The command below finds candidate lines. Check each match to exclude comments and strings, and use the PR's base branch in place of `origin/master` for a stacked PR.

```bash
git diff --no-color --unified=0 origin/master...HEAD -- '*.scala' build.mill | rg '^\+.*\b(while|var)\b'
```

## Lessons

Mistakes made by or with the agents, and the rule each one led to.

- Concurrent `./mill` clients in one worktree once made Mill run an agent twice in one invocation. `./mill exp.matrix` builds once and runs the agent and the evaluator from the classpath, and `AGENTS.md` keeps other Mill calls out of a worktree while the matrix runs.
- Generated Scala used `while`, `var` and nested `if`/`else` even next to functional code. The [Scala rule](../.claude/rules/scala.md#functional-style) now shows alternatives taken from the project.
- An agent merged a PR as soon as CI passed. The workflow now requires an explicit instruction from the user before an agent merges a PR.
- A scenario change left the server jar and the Docker image unable to simulate for two weeks: the jar kept only the first of the `META-INF/services` files its dependencies share and lost the Groovy script engine, while CI simulated only from the Mill classpath. The assembly now merges those files, and CI judges a program from the jar.
- The Mill port of the matrix wrote its retry passes as a for-comprehension with `todo = missing` after the generator, which computed the runs to do for all three passes before the first one: every run started three times and the first two executions were overwritten. The [Scala rule](../.claude/rules/scala.md#functional-style) now warns about value definitions in for-comprehensions, and `exp.test` covers the pass loop.
