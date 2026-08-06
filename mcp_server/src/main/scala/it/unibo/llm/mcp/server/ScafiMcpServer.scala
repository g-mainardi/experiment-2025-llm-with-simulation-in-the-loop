package it.unibo.llm.mcp.server

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider
import io.modelcontextprotocol.server.{McpAsyncServerExchange, McpServer, McpServerFeatures}
import io.modelcontextprotocol.spec.McpSchema._
import it.unibo.llm.mcp.server.utils.ScafiTestUtils
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.{ServletContextHandler, ServletHolder}
import reactor.core.publisher.Mono

import scala.concurrent.duration.Duration
import scala.io.Source
import scala.jdk.CollectionConverters._

class ScafiMcpServer {
  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[ScafiMcpServer])
  private val mapper = new JacksonMcpJsonMapper(new ObjectMapper())
  private val transport = new HttpServletSseServerTransportProvider.Builder()
    .sseEndpoint("/mcp/sse")
    .messageEndpoint("/mcp/message")
    .jsonMapper(mapper)
    .build()
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

    val (hasErrors, errors, imageBase64) = ScafiTestUtils.simulateProgram(program, durationTimeout)
    if (hasErrors) {
      Mono.error(new IllegalStateException(errors.mkString("\n")))
    } else {
      val annotations = new Annotations(List(Role.USER).asJava, 1)
      val image = new ImageContent(annotations, imageBase64.get, "image/png")
      val res = new CallToolResult(List[Content](image).asJava, false, null, Map.empty[String, AnyRef].asJava)
      Mono.just(res)
    }
  }

  def initialize(): Unit = {
    logger.info("Starting ScaFi MCP Server...")
    val mcpServer = McpServer.async(transport)
      .serverInfo("ScaFi Simulator MCP Server", "1.0.0")
      .capabilities(capabilities)
      .tools(compilationTool, simulationTool)
      .build()

    val contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS)
    contextHandler.setContextPath("/")
    val serverletHolder = new ServletHolder(transport)
    contextHandler.addServlet(serverletHolder, "/*")

    val server = new Server(8080)
    server.setHandler(contextHandler)

    try {
      server.start()
      logger.info("ScaFi MCP Server started on port 8080")
      Runtime.getRuntime.addShutdownHook(new Thread(() => {
        try {
          mcpServer.close()
          server.stop()
        } catch {
          case e: Exception => logger.error("Error during server shutdown", e)
        }
      }))
      server.join()
    } catch {
      case e: Exception =>
        logger.error("Error starting the server", e)
        mcpServer.close()
        throw new RuntimeException(e)
    }
  }
}
