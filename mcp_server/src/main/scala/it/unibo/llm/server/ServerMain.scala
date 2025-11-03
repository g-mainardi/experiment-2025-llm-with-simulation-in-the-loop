package it.unibo.llm.server

object ServerMain {
  def main(args: Array[String]): Unit = {
    new Server().initialize()
  }
}
