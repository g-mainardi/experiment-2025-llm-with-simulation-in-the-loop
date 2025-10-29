# Experiment LLM-in-the-Loop

## Testing the MCP server

This will generate the jar containing the MCP server.
```bash
./mill mcp_server.assembly
```

This will run the MCP server using the Model Context Protocol Inspector.
```bash
npx @modelcontextprotocol/inspector java -jar out/mcp_server/assembly.dest/out.jar
```