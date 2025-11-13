package it.unibo.llm.mcp.server

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider
import org.springframework.context.annotation.{Bean, Configuration}
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.function.{RouterFunction, ServerResponse}

@Configuration
@EnableWebMvc class McpConfig {
  @Bean def webMvcStreamableHttpServerTransportProvider(mapper: ObjectMapper) =
    new WebMvcStreamableServerTransportProvider(mapper, "/mcp/message")

  @Bean def mcpRouterFunction(transportProvider: WebMvcStreamableServerTransportProvider): RouterFunction[ServerResponse] =
    transportProvider.getRouterFunction
}