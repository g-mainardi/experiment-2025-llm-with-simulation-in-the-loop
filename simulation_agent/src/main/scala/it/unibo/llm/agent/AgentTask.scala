package it.unibo.llm.agent

import dev.langchain4j.mcp.McpToolProvider
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport
import dev.langchain4j.mcp.client.{DefaultMcpClient, McpClient}
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.input.PromptTemplate
import org.bsc.langgraph4j.agentexecutor.AgentExecutor

import java.io.File
import scala.jdk.CollectionConverters._

class AgentTask(private val jarFile: File, model: ChatModel, prompt: String) {
  private val transport = new StdioMcpTransport.Builder()
    .command(List(
      "java",
      "-jar",
      jarFile.getAbsoluteFile.toString
    ).asJava)
    .logEvents(true)
    .environment(Map.empty[String, String].asJava)
    .build()

  private val mcpClient = new DefaultMcpClient.Builder()
    .transport(transport)
    .build()

  private val agentBuilder = AgentExecutor.builder().chatModel(model)

  for (toolSpecification <- mcpClient.listTools().asScala) {
    agentBuilder.tool(toolSpecification, (request, _) => mcpClient.executeTool(request).resultText())
  }

  private val agent = agentBuilder.build().compile()
  private val message = PromptTemplate.from(prompt).apply(Map.empty.asJava).toUserMessage

  def execute(): String = {
    agent.invoke(Map[String, Object]("messages" -> message).asJava)
      .flatMap(e => e.finalResponse())
      .orElse("No response")
  }
}
