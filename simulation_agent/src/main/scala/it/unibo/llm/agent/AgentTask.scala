package it.unibo.llm.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.mcp.McpToolProvider
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport
import dev.langchain4j.mcp.client.DefaultMcpClient
import dev.langchain4j.mcp.client.McpToolResultConverter
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler
import dev.langchain4j.service.tool.ToolExecutionErrorHandler
import dev.langchain4j.service.tool.ToolExecutionResult

import java.util.{List => JList}
import java.util.{Map => JMap}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

trait Assistant {

  def chat(message: String): String

}

/** The default converter only accepts text: MCP image blocks (the simulation plot) reach the model as images. */
object ImageToolResultConverter extends McpToolResultConverter {

  override def convert(content: JList[JMap[String, Object]], isError: Boolean): ToolExecutionResult = {
    val contents = content.asScala.toList.map { item =>
      item.get("type") match {
        case "text"  => TextContent.from(String.valueOf(item.get("text")))
        case "image" => ImageContent.from(item.get("data").toString, item.get("mimeType").toString)
        case other   => throw new IllegalArgumentException(s"Unsupported MCP content type: $other")
      }
    }
    ToolExecutionResult.builder().isError(isError).resultContents(contents.asJava).build()
  }

}

/** Runs the prompt with the server's tools, for at most maxRoundTrips rounds of tool calls (maxRoundTrips + 1 model
  * requests, without retries).
  */
/** A tool call: tool name, JSON arguments, and whether the tool returned an error. */
final case class ToolCall(name: String, arguments: JsonNode, failed: Boolean)

class AgentTask(model: ChatModel, prompt: String, maxRoundTrips: Int) {

  private val logger = org.slf4j.LoggerFactory.getLogger("AgentTask")

  private val hostname = System.getenv().asScala.getOrElse("MCP_SERVER_HOSTNAME", "mcp-server")

  logger.info("MCP Server Hostname: {}", hostname)

  private val json = new ObjectMapper()

  /** Tool calls of the run, in order: the hooks fill it, so it is there also when the run ends with an exception. */
  val calls: ArrayBuffer[ToolCall] = ArrayBuffer.empty

  // The MCP client connects when built: built here, a server that is down fails the run, not the task's creation
  def execute(): String = {
    val transport = new StreamableHttpMcpTransport.Builder()
      .url("http://localhost:8080/mcp")
      .logResponses(true)
      .logRequests(true)
      .build()
    val mcpClient = new DefaultMcpClient.Builder()
      .transport(transport)
      .toolResultConverter(ImageToolResultConverter)
      .build()
    try assistant(mcpClient).chat(prompt)
    finally mcpClient.close()
  }

  // AiServices runs the tool loop: model -> MCP tool -> tool result back to the model, until a final answer
  private def assistant(mcpClient: DefaultMcpClient) = AiServices
    .builder(classOf[Assistant])
    .chatModel(model)
    .toolProvider(McpToolProvider.builder().mcpClients(mcpClient).build())
    .maxToolCallingRoundTrips(maxRoundTrips)
    // Wrong arguments: the model reads why and can retry
    .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
    // Failed compilations and simulations are tool results with isError, which the MCP client turns into errors
    // visible to the LLM; anything else is an infrastructure failure (server down) and ends the run
    .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
    .beforeToolExecution { e =>
      val arguments = json.readTree(e.request().arguments())
      calls += ToolCall(e.request().name(), arguments, failed = false)
      // Arguments are JSON with escaped newlines: print each one as plain text, so the code is readable
      val args = arguments.properties().asScala.map { entry =>
        s"\n--- ${entry.getKey}:\n${entry.getValue.asText()}"
      }
      logger.info("Calling {}{}", e.request().name(), args.mkString)
    }
    .afterToolExecution { e =>
      // Tools run one at a time: this is the call the last beforeToolExecution added
      calls(calls.length - 1) = calls.last.copy(failed = e.hasFailed)
      val result = e.resultContents().asScala.map {
        case text: TextContent   => text.text()
        case image: ImageContent => s"<${image.image().mimeType()} image>"
        case other               => other.toString
      }
      logger.info("{} returned{}:\n{}", e.request().name(), if (e.hasFailed) " an error" else "", result.mkString("\n"))
    }
    .build()

}

object AgentTask {

  /** LangChain4j 1.23 throws a plain RuntimeException ("Something is wrong, exceeded N tool calling round trips
    * (maxToolCallingRoundTrips)") when the model still calls tools after the last round trip.
    */
  def exceededRoundTrips(e: Throwable): Boolean =
    e.getClass == classOf[RuntimeException] && String.valueOf(e.getMessage).contains("(maxToolCallingRoundTrips)")

}
