---
paths:
  - "CLAUDE.md"
  - "AGENTS.md"
  - "**/.claude/**"
  - ".agents/**"
  - "docs/agentic-development.md"
---

# Instructions for Claude Code and Codex

- `AGENTS.md` holds only what every session needs, and `CLAUDE.md` imports it. What holds in one area of the repository is a rule with `paths:` in `.claude/rules/`: `scala.md`, `oracle.md`, `swarm-model.md`, `markdown.md` and this one. What holds in one module is in `<module>/.claude/rules/<module>.md` (`mcp_server`, `simulation_agent`, `scripts`), which loads when Claude touches a file of that module and ends with the State of the module: what is done and what is missing. A `.claude/CLAUDE.md` or `.claude/settings.json` inside a module is read only when Claude is started from that folder, so don't use them.
- A rule says what to do and check in its area and links to [Experiment internals](../../docs/experiment.md) for how things work, instead of repeating it. A change that moves a module forward updates its State in the same commit.
- The procedures the project repeats become skills in `.claude/skills/`.
- Codex reads only `AGENTS.md`: its *Rules by path* section lists the rules by hand, and Codex finds the skills through links in `.agents/skills/`. A new rule gets a line in `AGENTS.md`, a new skill a link in `.agents/skills/`.
- `CLAUDE.local.md` holds unversioned instructions for one checkout; `AGENTS.md` tells agents other than Claude Code to read it.
- When this organization changes, update [Agentic development](../../docs/agentic-development.md).
