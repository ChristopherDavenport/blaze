package org.http4s.blaze.http.http2.client

import java.nio.ByteBuffer

import org.http4s.blaze.channel.nio2.ClientChannelFactory
import org.http4s.blaze.http._
import org.http4s.blaze.http.util.UrlTools.UrlComposition
import org.http4s.blaze.http.http2.Http2Connection.Running
import org.http4s.blaze.http.http2._
import org.http4s.blaze.http.util.UrlTools
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.pipeline.{Command, HeadStage, LeafBuilder}
import org.http4s.blaze.util.Execution
import org.log4s.getLogger

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.Success


class Http2ClientSessionManagerImpl(
    config: HttpClientConfig,
    mySettings: Http2Settings)
  extends ClientSessionManager {

  private[this] val logger = getLogger
  private[this] val sessionCache = new mutable.HashMap[String, Future[Http2ClientConnection]]
  private[this] val factory = new ClientChannelFactory(group = config.group)


  override def acquireSession(request: HttpRequest): Future[HttpClientSession] = {
    val helper = UrlTools.UrlComposition(request.uri)

    sessionCache.synchronized {
      sessionCache.get(helper.authority) match {
        case Some(session) =>
          session.value match {
            case Some(Success(s)) if s.state == Running => session
            // we have either a session that is no good any more, or never was.
            case _ => makeAndStoreSession(helper)
          }

        case None =>
          makeAndStoreSession(helper)
      }
    }
  }


  /** Close the `SessionManager` and free any resources */
  override def close(): Unit = sessionCache.synchronized {
    sessionCache.values.foreach(_.onSuccess { case session => session.closeNow() }(Execution.directec))
    sessionCache.clear()
  }

  /** Return the session to the pool.
    *
    * Depending on the state of the session and the nature of the pool, this may
    * either cache the session for future use or close it.
    */
  override def returnSession(session: HttpClientSession): Unit = ()

  // starts to acquire a new session and adds the attempt to the sessionCache
  private def makeAndStoreSession(url: UrlComposition): Future[Http2ClientConnection] = {
    logger.debug(s"Creating a new session for composition $url")
    val fSession = factory.connect(url.getAddress).flatMap(initialPipeline)(Execution.directec)
    sessionCache.put(url.authority, fSession)
    fSession
  }

  private def initialPipeline(head: HeadStage[ByteBuffer]): Future[Http2ClientConnection] = {
    val p = Promise[Http2ClientConnection]

    def buildH2(s: String): LeafBuilder[ByteBuffer] = {
      logger.debug(s"Selected $s")
      if (s != "h2") {
        p.tryFailure(new Exception("Failed to negotiate h2. Selected: $s"))
        ???
      } else {
        val f = new DefaultFlowStrategy(mySettings)
        val handshaker = new Http2TlsClientHandshaker(mySettings, f, Execution.trampoline)
        p.completeWith(handshaker.clientSession)
        LeafBuilder(handshaker)
      }
    }

    val engine = config.getClientSslEngine()
    engine.setUseClientMode(true)

    LeafBuilder(new ALPNClientSelector(engine, Seq("h2"), buildH2))
      .prepend(new SSLStage(engine))
      .base(head)

    head.sendInboundCommand(Command.Connected)
    p.future.onComplete { r =>
    }(Execution.directec)
    p.future
  }
}
