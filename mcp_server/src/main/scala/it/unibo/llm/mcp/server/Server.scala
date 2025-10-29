package it.unibo.llm.mcp.server

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.server.{McpAsyncServerExchange, McpServer, McpServerFeatures}
import io.modelcontextprotocol.spec.McpSchema.{CallToolRequest, CallToolResult, ServerCapabilities, Tool}
import it.unibo.llm.mcp.server.utils.ScafiTestUtils
import reactor.core.publisher.Mono

import scala.concurrent.duration.Duration
import scala.io.Source

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
  private val simulationSchema = Source.fromResource("schemas/simulation_schema.json").mkString
  private val compilationTool = new McpServerFeatures.AsyncToolSpecification(
    Tool.builder()
      .title("Code Compilation Tool")
      .description("A tool to compile and check Scala code snippets for errors.")
      .name("compile_code")
      .inputSchema(mapper, compilationSchema)
      .build(),
    null,
    handleCompilation,
  )
  private val simulationTool = new McpServerFeatures.AsyncToolSpecification(
    Tool.builder()
      .title("Code Simulation Tool")
      .description("A tool to simulate ScaFi programs and check for runtime errors.")
      .name("simulate_code")
      .inputSchema(mapper, simulationSchema)
      .build(),
    null,
    handleSimulation,
  )

  private def handleCompilation(server: McpAsyncServerExchange, request: CallToolRequest): Mono[CallToolResult] = {
    val (hasErrors, errors) = ScafiTestUtils.compileAndGetErrors(request.arguments().get("program").toString)
    Mono.just(new CallToolResult(if (!hasErrors) { "compilation success" } else { errors.mkString("\n") }, hasErrors))
  }

  private def handleSimulation(server: McpAsyncServerExchange, request: CallToolRequest): Mono[CallToolResult] = {
    val program = request.arguments().get("program").toString
    val timeout = request.arguments().get("timeout").toString
    val durationTimeout = try Duration(s"${timeout}s") catch {
      case _: NumberFormatException => return Mono.just(new CallToolResult(s"Invalid timeout format: $timeout", true))
    }
    val (hasErrors, errors) = ScafiTestUtils.simulateProgram(program, durationTimeout)
    Mono.just(new CallToolResult(if (!hasErrors) { "simulation success" } else { errors.mkString("\n") }, hasErrors))
  }

  def initialize(): Unit = {
    McpServer.async(transport)
      .capabilities(capabilities)
      .tools(compilationTool, simulationTool)
      .build()
    ()
  }
}
