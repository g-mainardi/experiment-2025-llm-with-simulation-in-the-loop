package it.unibo.llm.mcp.server

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.{McpAsyncServerExchange, McpServer, McpServerFeatures}
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema.{CallToolRequest, CallToolResult, JsonSchema, ServerCapabilities, Tool}
import reactor.core.publisher.Mono

import scala.io.Source
import scala.jdk.CollectionConverters._

class Server {
  private val transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()))
  private val capabilities = ServerCapabilities.builder()
    .resources(false, true)
    .tools(true)
    .prompts(true)
    .logging()
    .build()
  private val compilationSchema = Source.fromResource("schemas/compilation_schema.json").mkString
  private val toolSpecification = new McpServerFeatures.AsyncToolSpecification(
    Tool.builder()
      .title("Code Compilation Tool")
      .description("A tool to compile and check Scala code snippets for errors.")
      .name("compile_code")
      .build(),
    null,
    handleCompilation,
  )
  private val mcpServer = McpServer.async(transport)
    .capabilities(capabilities)
    .tools(toolSpecification)
    .build()

  private def handleCompilation(server: McpAsyncServerExchange, request: CallToolRequest): Mono[CallToolResult] = {
    Mono.just(new CallToolResult(List.empty.asJava, false, null, Map.empty[String, AnyRef].asJava))
  }
}
