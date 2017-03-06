package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine

import org.http4s.blaze.http._
import org.http4s.blaze.http.http1.server.Http1ServerStage
import org.http4s.blaze.http.http2.{Http2Settings, StreamMessage}
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}
import org.log4s.getLogger

object Http2ServerSelector {

  private val logger = getLogger

  private val HTTP_1_1 = "http/1.1"
  private val H2       = "h2"
  private val H2_14    = "h2-14"

  def apply(engine: SSLEngine, 
           service: HttpService,
            config: HttpServerStageConfig): ALPNServerSelector = {

    def builder(s: String): LeafBuilder[ByteBuffer] = s match {
      case H2 | H2_14 => LeafBuilder(http2Stage(service, config))
      case _          => LeafBuilder(http1xStage(service, config))
    }

    def selector(protocols: Seq[String]): String = {
      protocols.find {
        case H2    => true
        case H2_14 => true
        case _     => false
      } getOrElse(HTTP_1_1)
    }
    
    new ALPNServerSelector(engine, selector, builder)
  }

  private def http1xStage(service: HttpService, config: HttpServerStageConfig): TailStage[ByteBuffer] =
    new Http1ServerStage(service, config)

  private def http2Stage(service: HttpService, config: HttpServerStageConfig): TailStage[ByteBuffer] = {
    logger.info("Selected HTTP2")

    def newNode(streamId: Int): LeafBuilder[StreamMessage] = {
      LeafBuilder(new Http2ServerStage(streamId, service, config))
    }

    val mySettings = Http2Settings.default()
    mySettings.maxHeaderListSize = config.maxNonBodyBytes

    new Http2TlsServerHandshaker(mySettings, newNode)

//    Http2Stage(
//      nodeBuilder = newNode,
//      timeout = Duration.Inf,
//      ec = config.serviceExecutor,
//      maxHeadersLength = config.maxNonBodyBytes
//    )
  }
}
