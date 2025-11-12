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
./mill simulation_agent.run "out/mcp_server/assembly.dest/out.jar" "gemini-2.5-pro" "<task description>"
```
