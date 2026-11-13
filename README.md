# Experiment LLM-in-the-Loop

## Testing the MCP server

This will generate the jar containing the MCP server.

```bash
./mill mcp_server.assembly
```

To execute the server, run:

```bash
java -jar out/mcp_server/assembly.dest/out.jar
```

or using Docker:

```bash
docker build -f server.Dockerfile -t scafi-mcp-server:latest .
docker run --rm -it -p 8080:8080 scafi-mcp-server:latest
```

or using docker-compose:

```bash
docker compose up
```

To check that the server tools works, connect the [MCP Inspector](https://github.com/modelcontextprotocol/inspector) to it
while it is running (needs Node.js):

```bash
npx @modelcontextprotocol/inspector --web --transport http --server-url http://localhost:8080/mcp
```

It opens a web UI that lists the `compile_code` and `simulate_code` tools and lets you call them: passing the source of
`mcp_server/src/main/scala/it/unibo/scafi/CircleFormation.scala` as `program` to `simulate_code` should return the plot
of a circle.

## Start the agent

With the MCP server running (see above):

```bash
set -a; . ./.env; set +a   # Mill doesn't load .env
./mill simulation_agent.run gemini-3.5-flash-lite circle-bug-half --pieces=goal,procedure-full,scala3,parameters,classpath,api,simulate-first,stop --max-round-trips=20
```

The two positional arguments are the model and the task; everything else is an optional `--name=value`:

- model: names starting with `gemini` use Google AI, and need `GOOGLE_API_KEY` (kept in `.env`, e.g. `GOOGLE_API_KEY=...`); use the most recent Flash Lite (`gemini-3.5-flash-lite` as of 2026-11). Any other name uses the local Ollama instance at `localhost:11434`, where the model must already be available (e.g. `ollama pull qwen3-vl:2b`).
- task: `circle-control` starts from a correct circle formation program, `circle-bug-half`, `circle-bug-maxby` and `circle-bug-absolute` from copies with one bug each, behind the same goal. Tasks are listed in `simulation_agent/src/main/scala/it/unibo/llm/agent/Tasks.scala`.
- `--pieces`: the prompt pieces, comma separated, from `simulation_agent/src/main/resources/prompts/<name>.txt`; `goal` is the task's goal. They come in the given order before `Run the simulation of this:` and the starting program; without them (the default) the prompt is just that, the original prompt of the experiment.
- `--condition`: the feedback the model gets: `full` (the default) gives it `compile_code` and `simulate_code` with the plot, `compile` only `compile_code`, `none` no tools, so it can only read the code. Pieces that ask to simulate (`procedure-full`, `simulate-first`, `stop`, `parameters`) only make sense with `full`; `procedure-compile` and `procedure-none` tell the model what it can do in the other two. Without `full` the line before the program is `Here is the program:`.
- `--max-round-trips`: rounds of tool calls before the run stops without a final answer (default 20, i.e. at most 21 model requests).

The terminal shows each tool call, its result and the final answer; `/tmp/simulation_agent.log` has the full debug log. Each run also writes a JSON record to `runs/` with outcome (`answered`, `limit` or `error`), tool calls, final program and cost; its fields are described in `CLAUDE.md`. An unknown task, piece or option prints the usage.

To judge whether the final program of each run really forms the circle, run `./mill mcp_server.runMain it.unibo.llm.mcp.server.Evaluate "$PWD/runs"`: it simulates each final program on three seeds, with the scenario defaults and the target radius, and writes the verdict and the metrics in the record (`evaluation`, described in `CLAUDE.md`). It needs no model requests and the agent never sees its result. Simulations run in parallel (`--threads=n`, default half the logical cores) and identical programs are simulated once, with the same results as one at a time.

To compare the feedback conditions, `scripts/matrix.sh [dir] [k] [jobs]` (server running) runs each task in four cells, k times (default 10), `jobs` runs at a time (default 4): the full information base with `none`, `compile` and `full`, and the bare original prompt with `full`. The records go to `dir` (default `runs/matrix-<date>`) as `<cell>--<task>--rNN.json`; `Evaluate` judges them while the runs go on, then the script runs `scripts/summary.py`, which writes `summary.csv`: success rate from the oracle, outcomes, requests, tokens and time per task and cell, and the baseline of Olausson et al. (ICLR 2024) for `none`. Run it again after an interruption: it skips the runs already there.

## Contributing

Branch from `master` and open a pull request: `master` only accepts PRs with a green CI, merged with rebase or squash.

### Git hooks

The hooks live in `.githooks/` and are enabled by the first `./mill` command you run, which sets `core.hooksPath`:

- `commit-msg` requires [Conventional Commits](https://www.conventionalcommits.org/): `type(scope)!: description`,
  with type one of `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`;
- `pre-commit` rejects the commit when the Scala sources are not formatted.

CI runs the same checks on every pull request.

### Formatting

The rules are in `.scalafmt.conf`, so you don't need to know them: format the whole project with

```bash
./mill mill.scalalib.scalafmt/reformatAll
```

or let the editor format on save (both read `.scalafmt.conf`):

- VS Code with Metals: add `"[scala]": { "editor.formatOnSave": true }` to the settings;
- IntelliJ IDEA with the Scala plugin: set *Settings → Editor → Code Style → Scala → Formatter* to *Scalafmt*, then
  enable *Reformat code* in *Settings → Tools → Actions on Save*.
