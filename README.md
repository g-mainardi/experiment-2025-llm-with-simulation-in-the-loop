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

## Start the agent

```bash
./mill simulation_agent.run "gemini-3.6-flash"
```

The argument is the model name, and it selects the backend:

- names starting with `gemini` use Google AI: the `GOOGLE_API_KEY` environment variable must be set
  (e.g. `export GOOGLE_API_KEY=...`);
- any other name uses the local Ollama instance at `localhost:11434`: the model must already be available in Ollama
  (e.g. `ollama pull qwen3-vl:2b`).
