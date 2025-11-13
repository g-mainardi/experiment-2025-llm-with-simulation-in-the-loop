package it.unibo.llm.mcp.server

import it.unibo.llm.mcp.server.tool.ScafiTools
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class SpringMain

object SpringMain {
  def main(args: Array[String]): Unit =
    SpringApplication.run(classOf[SpringMain], args: _*)
  /*
  @Bean
  def myTools(scafiTools: ScafiTools): ToolCallbackProvider = {
    MethodToolCallbackProvider.builder()
      .toolObjects(scafiTools)
      .build()
  }*/
}
