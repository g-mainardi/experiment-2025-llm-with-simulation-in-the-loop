package it.unibo.llm.mcp.server

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.{McpAsyncServerExchange, McpServer, McpServerFeatures}
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema.{CallToolRequest, CallToolResult, ServerCapabilities, Tool}
import it.unibo.llm.mcp.server.utils.ScafiTestUtils
import reactor.core.publisher.Mono

import scala.io.Source
import scala.jdk.CollectionConverters._

class Server {
  private val mapper = new JacksonMcpJsonMapper(new ObjectMapper())
  private val transport = new StdioServerTransportProvider(mapper)
  private val capabilities = ServerCapabilities.builder()
    .resources(false, false)
    .tools(true)
    .prompts(false)
    .logging()
    .build()
  private val compilationSchema = Source.fromResource("schemas/compilation_schema.json").mkString
  private val toolSpecification = new McpServerFeatures.AsyncToolSpecification(
    Tool.builder()
      .title("Code Compilation Tool")
      .description("A tool to compile and check Scala code snippets for errors.")
      .name("compile_code")
      .inputSchema(mapper, compilationSchema)
      .build(),
    null,
    handleCompilation,
  )

  private def handleCompilation(server: McpAsyncServerExchange, request: CallToolRequest): Mono[CallToolResult] = {
    val (hasErrors, errors) = ScafiTestUtils.compileAndGetErrors(request.arguments().get("program").toString)
    Mono.just(new CallToolResult(if (!hasErrors) { "compilation success" } else { errors.mkString }, hasErrors))
  }

  def initialize(): Unit = {
    McpServer.async(transport)
      .capabilities(capabilities)
      .tools(toolSpecification)
      .build()
    ()
  }
}
