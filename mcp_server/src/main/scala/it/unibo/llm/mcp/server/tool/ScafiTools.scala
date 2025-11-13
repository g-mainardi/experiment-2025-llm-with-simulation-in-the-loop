package it.unibo.llm.mcp.server.tool

import io.modelcontextprotocol.spec.McpSchema.{Annotations, CallToolResult, Content, ImageContent, Role}
import it.unibo.llm.mcp.server.utils.ScafiTestUtils
import org.springaicommunity.mcp.annotation.{McpTool, McpToolParam}
import org.springframework.stereotype.{Component, Service}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._

@Component
class ScafiTools {
  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[ScafiTools])
  @McpTool(
    name = "compile_scafi",
    description = "Compile and run a ScaFi program"
  )
  def compileScafi(
      @McpToolParam(description = "The ScaFi program code to compile and run", required = true)
      program: String
  ): String = {

    logger.info("Calling compile scafi...")
    val (hasErrors, errors) = ScafiTestUtils.compileAndGetErrors(program)
    if (!hasErrors) { "compilation success" }
    else { errors.mkString("\n") }
  }

  @McpTool(name = "simulate_scafi", description = "Simulate a ScaFi program")
  def simulateScafi(
      @McpToolParam(description = "The ScaFi program code to simulate", required = true)
      program: String,
      @McpToolParam(description = "The timeout for the simulation in seconds", required = false)
      timeout: java.lang.Double
  ): CallToolResult = {
    logger.info("Calling simulate scafi...")
    val (hasErrors, errors, imageBase64) =
      ScafiTestUtils.simulateProgram(program, Duration.apply(timeout, TimeUnit.SECONDS))
    if (hasErrors) {
      throw new IllegalStateException(errors.mkString("\n"))
    } else {
      val annotations = new Annotations(List(Role.USER).asJava, 1)
      val image = new ImageContent(annotations, imageBase64.get, "image/png")
      new CallToolResult(List[Content](image).asJava, false, null, Map.empty[String, AnyRef].asJava)
    }
  }
}
