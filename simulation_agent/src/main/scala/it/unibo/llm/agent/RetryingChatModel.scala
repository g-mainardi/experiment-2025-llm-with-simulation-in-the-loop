package it.unibo.llm.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.langchain4j.exception.RetriableException
import dev.langchain4j.exception.TimeoutException
import dev.langchain4j.model.ModelProvider
import dev.langchain4j.model.chat.Capability
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ChatRequestParameters
import dev.langchain4j.model.chat.response.ChatResponse

import scala.jdk.CollectionConverters._
import scala.util.Try

/** Retries a model call that fails on quota (429) or overload (503), waiting as long as Gemini asks or with a backoff
  * of 5, 10, 20, 40, 60 s. LangChain4j's own retries wait 0.5 and 0.75 s, too little for either. Retrying the single
  * call keeps the tool loop where it is, while retrying the whole task would restart the conversation from scratch. A
  * timeout is retried only maxTimeoutRetries times: a model that did not answer in 60 s rarely answers later.
  */
class RetryingChatModel(model: ChatModel, maxRetries: Int = 5, maxTimeoutRetries: Int = 1) extends ChatModel {

  import RetryingChatModel.geminiError

  private val logger = org.slf4j.LoggerFactory.getLogger("RetryingChatModel")

  override def doChat(request: ChatRequest): ChatResponse = send(request, retry = 0)

  override def defaultRequestParameters(): ChatRequestParameters = model.defaultRequestParameters()

  override def supportedCapabilities(): java.util.Set[Capability] = model.supportedCapabilities()

  override def provider(): ModelProvider = model.provider()

  private def send(request: ChatRequest, retry: Int): ChatResponse = {
    // Without it the terminal stays silent while the model thinks, which looks like a hang
    logger.info("Waiting for {}...", request.modelName())
    try model.chat(request)
    catch {
      case e: RetriableException if retry < retryLimit(e) && !dailyQuotaExceeded(e) =>
        val delay  = retryDelay(e).getOrElse(math.min(5 << retry, 60))
        val status = geminiError(e).map(_.path("status").asText()).getOrElse(e.getClass.getSimpleName)
        logger.warn("Model call failed with {}, retrying in {}s ({}/{})", status, delay, retry + 1, retryLimit(e))
        Thread.sleep(delay * 1000L)
        send(request, retry + 1)
    }
  }

  private def retryLimit(e: Exception): Int = e match {
    case _: TimeoutException => maxTimeoutRetries
    case _                   => maxRetries
  }

  private def details(e: Exception): List[JsonNode] =
    geminiError(e).toList.flatMap(_.path("details").asScala)

  // Seconds Gemini asks to wait ("8s", rounded down from the 8.6 s in the message, hence the extra second)
  private def retryDelay(e: Exception): Option[Int] =
    details(e)
      .find(_.path("@type").asText().endsWith("RetryInfo"))
      .flatMap(d => d.path("retryDelay").asText().stripSuffix("s").toDoubleOption)
      .map(_.ceil.toInt + 1)

  // A daily quota does not come back within the retries: fail at once
  private def dailyQuotaExceeded(e: Exception): Boolean =
    details(e).exists(_.path("violations").asScala.exists(_.path("quotaId").asText().contains("PerDay")))

}

object RetryingChatModel {

  private val json = new ObjectMapper()

  // The exception message is Gemini's error body: {"error": {"code", "message", "status", "details": [...]}}
  def geminiError(e: Exception): Option[JsonNode] =
    Try(json.readTree(e.getMessage).path("error")).toOption.filterNot(_.isMissingNode)

}
