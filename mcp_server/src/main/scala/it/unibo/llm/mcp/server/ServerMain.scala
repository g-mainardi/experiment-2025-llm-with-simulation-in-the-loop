package it.unibo.llm.mcp.server

object ServerMain {
  def main(args: Array[String]): Unit = {
    new ScafiMcpServer().initialize()
  }
}
