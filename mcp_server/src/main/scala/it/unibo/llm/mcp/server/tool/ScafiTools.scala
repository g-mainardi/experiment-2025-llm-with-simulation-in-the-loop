package it.unibo.llm.mcp.server.service

import org.springaicommunity.mcp.annotation.McpTool
import org.springframework.stereotype.Service

@Service
//@Slf4j
class ScafiService {
  @McpTool(name = "compile_scafi", description = "Compile and run a ScaFi program")
  def compileScafi(code: String): String = {
    "ciao"
  }

  @McpTool(name = "simulate_scafi", description = "Simulate a ScaFi program")
  def simulateScafi(code: String, nodes: Int): String = {
    "ciao"
  }
}
