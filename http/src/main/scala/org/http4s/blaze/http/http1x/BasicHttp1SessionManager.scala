package org.http4s.blaze.http.http1x

import java.net.{InetSocketAddress, URI}
import java.nio.ByteBuffer

import org.http4s.blaze.channel.nio2.ClientChannelFactory
import org.http4s.blaze.http.HttpRequest
import org.http4s.blaze.http.client.{HttpClientConfig, HttpClientSession, SessionManager}
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.pipeline.{Command, HeadStage, LeafBuilder}
import org.http4s.blaze.util.Execution
import org.log4s.getLogger

import scala.concurrent.Future

class BasicHttp1SessionManager(
    config: HttpClientConfig)
  extends SessionManager {
  import BasicHttp1SessionManager._

  private[this] val logger = getLogger

  private[this] val factory = new ClientChannelFactory(group = config.group)

  override def acquireSession(request: HttpRequest): Future[HttpClientSession] = {
    val urlComposition = UrlBreakdown(request.uri)  // TODO: this could fail
    logger.debug(s"Attempting to acquire session for address ${request.uri} with url decomposition $urlComposition")
    factory.connect(urlComposition.getAddress)
      .map(connectPipeline(urlComposition, _))(Execution.directec)
  }

  // We just close all sessions
  override def returnSession(session: HttpClientSession): Unit =
    releaseSession(session)

  // We just close all sessions
  override def releaseSession(session: HttpClientSession): Unit =
    session.close()

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

private object BasicHttp1SessionManager {

  case class UrlComposition(uri: URI) {

    def scheme: String = uri.getScheme

    def isTls: Boolean = scheme.equalsIgnoreCase("https")

    def path: String = uri.getPath

    def fullPath: String =
      if (uri.getQuery != null) path + "?" + uri.getQuery
      else path

    def getAddress: InetSocketAddress = {
      val port =
        if (uri.getPort > 0) uri.getPort
        else (if (uri.getScheme.equalsIgnoreCase("http")) 80 else 443)
      new InetSocketAddress(uri.getHost, port)
    }
  }

  object UrlBreakdown {
    def apply(url: String): UrlComposition = {
      val uri = java.net.URI.create(if (isPrefixedWithHTTP(url)) url else "http://" + url)
      UrlComposition(uri)
    }
  }

  private[this] def isPrefixedWithHTTP(string: String): Boolean = {
    string.length >= 4 &&
      (string.charAt(0) == 'h' || string.charAt(0) == 'H') &&
      (string.charAt(1) == 't' || string.charAt(1) == 'T') &&
      (string.charAt(2) == 't' || string.charAt(2) == 'T') &&
      (string.charAt(3) == 'p' || string.charAt(3) == 'P')
  }
}