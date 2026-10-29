package it.unibo.llm.mcp.server

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema._
import it.unibo.llm.mcp.server.utils.ScafiTestUtils
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import reactor.core.publisher.Mono

import scala.concurrent.duration.Duration
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

class ScafiMcpServer {

  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[ScafiMcpServer])

  private val mapper = McpJsonDefaults.getMapper

  private val transport = new HttpServletStreamableServerTransportProvider.Builder()
    .mcpEndpoint("/mcp")
    .jsonMapper(mapper)
    .build()

  private val capabilities = ServerCapabilities
    .builder()
    .resources(false, false)
    .tools(true)
    .prompts(false)
    .logging()
    .build()

  private val compilationSchema = Source.fromResource("schemas/compilation_schema.json").mkString

  private val simulationSchema = Source.fromResource("schemas/simulation_schema.json").mkString

  private val compilationTool = McpServerFeatures.AsyncToolSpecification
    .builder()
    .tool(
      Tool
        .builder("compile_code", mapper, compilationSchema)
        .title("Code Compilation Tool")
        .description("A tool to compile and check Scala code snippets for errors.")
        .build()
    )
    .callHandler(handleCompilation)
    .build()

  private val simulationTool = McpServerFeatures.AsyncToolSpecification
    .builder()
    .tool(
      Tool
        .builder("simulate_code", mapper, simulationSchema)
        .title("Code Simulation Tool")
        .description(
          "A tool to simulate ScaFi programs and check for runtime errors. The optional parameters set these " +
            s"scenario variables:\n${ScafiTestUtils.variablesDescription}"
        )
        .build()
    )
    .callHandler(handleSimulation)
    .build()

  private def handleCompilation(server: McpAsyncServerExchange, request: CallToolRequest): Mono[CallToolResult] = {
    val (hasErrors, errors) = ScafiTestUtils.compileAndGetErrors(request.arguments().get("program").toString)
    Mono.just(
      textResult(if (!hasErrors) "compilation success" else errors.mkString("\n"), hasErrors)
    )
  }

  private def handleSimulation(server: McpAsyncServerExchange, request: CallToolRequest): Mono[CallToolResult] = {
    val program = request.arguments().get("program").toString
    // Optional in the schema: clients don't have to send the default
    val timeout         = Option(request.arguments().get("timeout")).getOrElse(60).toString
    val durationTimeout =
      try Duration(s"${timeout}s")
      catch {
        case _: NumberFormatException =>
          return Mono.just(textResult(s"Invalid timeout format: $timeout", isError = true))
      }

    val parameters = Option(request.arguments().get("parameters"))
      .fold(Map.empty[String, Any])(_.asInstanceOf[java.util.Map[String, Any]].asScala.toMap)

    // Any failure goes back as a tool error the model can read, not as a protocol error
    val (hasErrors, errors, imageBase64) =
      try ScafiTestUtils.simulateProgram(program, durationTimeout, parameters)
      catch {
        case NonFatal(e) =>
          logger.warn("Simulation failed", e)
          (true, List(s"Simulation error: $e"), None)
      }
    if (hasErrors) {
      Mono.just(textResult(errors.mkString("\n"), isError = true))
    } else {
      val annotations = Annotations.builder().audience(List(Role.USER).asJava).priority(1.0).build()
      val image       = ImageContent.builder(imageBase64.get, "image/png").annotations(annotations).build()
      // Notes that don't make the run fail, e.g. a timeout
      val builder = CallToolResult.builder().addContent(image).isError(false)
      errors.foreach(builder.addTextContent)
      Mono.just(builder.build())
    }
  }

  private def textResult(text: String, isError: Boolean): CallToolResult =
    CallToolResult.builder().addTextContent(text).isError(isError).build()

  def initialize(): Unit = {
    logger.info("Starting ScaFi MCP Server...")
    val mcpServer = McpServer
      .async(transport)
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
