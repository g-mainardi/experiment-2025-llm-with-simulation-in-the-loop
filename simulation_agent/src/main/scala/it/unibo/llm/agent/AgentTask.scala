package it.unibo.llm.agent

import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.mcp.McpToolProvider
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport
import dev.langchain4j.mcp.client.DefaultMcpClient
import dev.langchain4j.mcp.client.McpToolResultConverter
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.tool.ToolExecutionResult

import java.util.{List => JList}
import java.util.{Map => JMap}
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

class AgentTask(model: ChatModel, prompt: String) {

  private val logger = org.slf4j.LoggerFactory.getLogger("AgentTask")

  private val hostname = System.getenv().asScala.getOrElse("MCP_SERVER_HOSTNAME", "mcp-server")

  logger.info("MCP Server Hostname: {}", hostname)

  private val transport = new StreamableHttpMcpTransport.Builder()
    .url("http://localhost:8080/mcp")
    .logResponses(true)
    .logRequests(true)
    .build()

  private val mcpClient = new DefaultMcpClient.Builder()
    .transport(transport)
    .toolResultConverter(ImageToolResultConverter)
    .build()

  // AiServices runs the tool loop: model -> MCP tool -> tool result back to the model, until a final answer
  private val assistant = AiServices
    .builder(classOf[Assistant])
    .chatModel(model)
    .toolProvider(McpToolProvider.builder().mcpClients(mcpClient).build())
    .build()

  def execute(): String =
    try assistant.chat(prompt)
    finally mcpClient.close()

}
