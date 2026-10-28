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

```bash
./mill simulation_agent.run "gemini-3.6-flash"
```

The argument is the model name, and it selects the backend:

- names starting with `gemini` use Google AI: the `GOOGLE_API_KEY` environment variable must be set
  (e.g. `export GOOGLE_API_KEY=...`);
- any other name uses the local Ollama instance at `localhost:11434`: the model must already be available in Ollama
  (e.g. `ollama pull qwen3-vl:2b`).

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
