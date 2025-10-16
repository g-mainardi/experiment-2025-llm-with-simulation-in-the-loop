package it.unibo.llm.mcp.server

object ServerMain {
  def main(args: Array[String]): Unit = {
    new Server()
    println("MCP Server started")
  }
}

