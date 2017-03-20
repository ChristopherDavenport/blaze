package org.http4s.blaze.http.http1.client

import java.nio.ByteBuffer

import org.http4s.blaze.channel.nio2.ClientChannelFactory
import org.http4s.blaze.http.{ClientSessionManager, HttpClientConfig, HttpClientSession, HttpRequest}
import org.http4s.blaze.http.util.UrlTools.UrlComposition
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.pipeline.{Command, HeadStage, LeafBuilder}
import org.http4s.blaze.util.Execution
import org.log4s.getLogger

import scala.concurrent.Future
import scala.concurrent.duration.Duration

private[http] final class BasicHttp1ClientSessionManager(
    config: HttpClientConfig)
  extends ClientSessionManager {

  private[this] val logger = getLogger
  private[this] val factory = new ClientChannelFactory(group = config.group)

  override def acquireSession(request: HttpRequest): Future[HttpClientSession] = {
    val urlComposition = UrlComposition(request.url)  // TODO: this could fail
    logger.debug(s"Attempting to acquire session for address ${request.url} with url decomposition $urlComposition")
    factory.connect(urlComposition.getAddress)
      .map(connectPipeline(urlComposition, _))(Execution.directec)
  }

  // We just close all sessions
  override def returnSession(session: HttpClientSession): Unit =
  session.close(Duration.Zero)

  /** Close the `SessionManager` and free any resources */
  override def close(): Unit = ()

  // Build the http/1.x pipeline, including ssl if necessary
  // TODO: this would be useful for a pooled SessionPool as well.
  private def connectPipeline(url: UrlComposition, head: HeadStage[ByteBuffer]): HttpClientSession = {
    val clientStage = new Http1ClientStage(config)
    var builder = LeafBuilder(clientStage)
    if (url.isTls) {
      builder = builder.prepend(new SSLStage(config.getClientSslEngine()))
    }
    builder.base(head)
    head.sendInboundCommand(Command.Connected)
    clientStage
  }
}
