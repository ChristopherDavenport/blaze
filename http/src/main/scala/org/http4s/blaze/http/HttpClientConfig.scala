package org.http4s.blaze.http

import java.nio.channels.AsynchronousChannelGroup
import javax.net.ssl.{SSLContext, SSLEngine}

import org.http4s.blaze.util.GenericSSLContext

// TODO: maybe there should be a separate SslConfig

case class HttpClientConfig(
    maxRequestLine: Int = 2*1048,
    maxHeaderLength: Int = 8*1024,
    lenientParser: Boolean = false,
    group: Option[AsynchronousChannelGroup] = None,
    sslContext: Option[SSLContext] = None) {

  private lazy val theSslContext = sslContext.getOrElse(GenericSSLContext.clientSSLContext())

  /** Get a new SSlEngine set to ClientMode.
    *
    * If the SslContext of this config is not defined, the default
    * will be used.
    */
  def getClientSslEngine(): SSLEngine = {
    val engine = theSslContext.createSSLEngine()
    engine.setUseClientMode(true)
    engine
  }
}

object HttpClientConfig {
  val Default = HttpClientConfig()
}