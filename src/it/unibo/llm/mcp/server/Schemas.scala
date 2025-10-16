package it.unibo.llm.mcp.server

object Schemas {
  case class CompilationSchema(
    `type`: String = "object",
    id: String = "urn:jsonschema:Operation",
    properties: CompilationSchemaProperties
  )
  case class CompilationSchemaProperties(
      code: String
  )
}