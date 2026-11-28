---
paths:
  - "CLAUDE.md"
  - "AGENTS.md"
  - "**/.claude/**"
  - ".agents/**"
  - "docs/agentic-development.md"
---

# Instructions for coding agents

- The setup targets a generic coding agent, not one tool. Write it in Claude Code's format and state in `AGENTS.md` what other agents need to follow it. It is used with Claude Code, Codex and Antigravity CLI (`agy`).

- `AGENTS.md` holds only what every session needs, and `CLAUDE.md` imports it. Keep `CLAUDE.md`: Claude Code skips `AGENTS.md` when a `CLAUDE.local.md` is present, as in every checkout. What holds in one area of the repository is a rule with `paths:` in `.claude/rules/`: `scala.md`, `oracle.md`, `swarm-model.md`, `markdown.md` and this one. What holds in one module is in `<module>/.claude/rules/<module>.md` (`mcp_server`, `simulation_agent`, `exp`, `scripts`), which loads when Claude touches a file of that module and ends with the State of the module: what is done and what is missing. A `.claude/CLAUDE.md` or `.claude/settings.json` inside a module is read only when Claude is started from that folder, so don't use them.
- A rule says what to do and check in its area and links to [Experiment internals](../../docs/experiment.md) for how things work, instead of repeating it. A change that moves a module forward updates its State in the same commit.
- The procedures the project repeats become skills in `.agents/skills/<skill>/SKILL.md`, the Agent Skills path that Codex and agy read. Claude Code reads only `.claude/skills/`, so each skill also gets a relative symlink `.claude/skills/<skill>` -> `../../.agents/skills/<skill>`. Keep Claude-only frontmatter fields optional: other agents ignore them.
- Other agents read `AGENTS.md` but not `.claude/rules/`: its *Rules by path* section lists the rules by hand, so a new rule gets a line there.
- Hooks, permissions, MCP servers and subagents are applied by each agent's harness, not by the model, so `AGENTS.md` cannot share them. Put a check that must always run in the git hooks or in CI.
- `CLAUDE.local.md` holds unversioned instructions for one checkout; `AGENTS.md` tells agents other than Claude Code to read it.
- When this organization changes, update [Agentic development](../../docs/agentic-development.md).
