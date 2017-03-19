package org.http4s.blaze.http

import org.http4s.blaze.channel.nio2.ClientChannelFactory
import org.http4s.blaze.http.HttpClientSession.{Closed, ReleaseableResponse, Status}
import org.http4s.blaze.http.http1.client.Http1ClientStage
import org.http4s.blaze.http.http2.client.Http2ClientSelector
import org.http4s.blaze.http.util.UrlTools.UrlComposition
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.util.Execution
import org.log4s.getLogger

import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

// TODO: lower the logging level from info to debug
private class ClientSessionManagerImpl(config: HttpClientConfig) extends ClientSessionManager {

  private[this] case class ConnectionId(scheme: String, authority: String)

  // Used to couple the connection information to the session
  private[this] case class Http1SessionProxy(id: ConnectionId, parent: Http1ClientSession) extends Http1ClientSession {
    override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = parent.dispatch(request)
    override def status: Status = parent.status
    override def close(within: Duration): Unit = parent.close(within)
  }

  private[this] def getId(composition: UrlComposition): ConnectionId =
    ConnectionId(composition.scheme, composition.authority)

  private[this] implicit def ec = Execution.trampoline

  private[this] val logger = getLogger
  private[this] val socketFactory = new ClientChannelFactory(group = config.group)
  private[this] val http2ClientSelector = new Http2ClientSelector(config)

  // We will lock when mutating the sessionCache or its underlying sessions
  private[this] val sessionCache = new java.util.HashMap[ConnectionId, java.util.Collection[HttpClientSession]]

  override def acquireSession(request: HttpRequest): Future[HttpClientSession] = {
    logger.info(s"Acquiring session for request $request")
    val urlComposition = UrlComposition(request.uri)  // TODO: this could fail
    val id = getId(urlComposition)
    val session = findExistingSession(id)

    if (session == null) createNewSession(urlComposition, id)
    else {
      logger.info(s"Found hot session for id $id: $session")
      Future.successful(session)
    }
  }

  private[this] def findExistingSession(id: ConnectionId): HttpClientSession = sessionCache.synchronized {
    var session: HttpClientSession = null

    def printit(i: Http2ClientSession): Boolean = {
      println(s"Quality: ${i.quality}, is closed: ${i.isClosed}")
      false
    }

    sessionCache.get(id) match {
      case null => () // nop
      case sessions =>
        val it = sessions.iterator
        while(session == null && it.hasNext) it.next() match {
          case v: Http2ClientSession if printit(v) => ???
          case h2: Http2ClientSession if h2.quality > 0.1 =>
            session = h2

          case h2: Http2ClientSession if h2.status == Closed =>
            it.remove()

          case _: Http2ClientSession => () // nop

          case h1: Http1ClientSession =>
            it.remove()
            if (h1.status != Closed) { // Should never be busy
              session = h1 // found a suitable HTTP/1.x session.
            }
        }

        // if we took the last session, drop the collection
        if (sessions.isEmpty) {
          sessionCache.remove(id)
        }
    }

    session
  }

  private[this] def createNewSession(urlComposition: UrlComposition, id: ConnectionId): Future[HttpClientSession] = {
    logger.info(s"Creating new session for id $id")
    val p = Promise[HttpClientSession]

    socketFactory.connect(urlComposition.getAddress).onComplete {
      case Failure(e) => p.tryFailure(e)
      case Success(head) =>
        if (urlComposition.scheme == "https") {
          val engine = config.getClientSslEngine()
          engine.setUseClientMode(true)

          val rawSession = Promise[HttpClientSession]

          LeafBuilder(http2ClientSelector.newStage(engine, rawSession))
            .prepend(new SSLStage(engine))
            .base(head)
          head.sendInboundCommand(Command.Connected)

          p.completeWith(rawSession.future.map {
            case h1: Http1ClientSession =>
              new Http1SessionProxy(id, h1)

            case h2: Http2ClientSession =>
              addSessionToCache(id, h2)
              h2
          })

        } else {
          val clientStage = new Http1ClientStage(config)
          var builder = LeafBuilder(clientStage)
          builder.base(head)
          head.sendInboundCommand(Command.Connected)
          p.trySuccess(new Http1SessionProxy(id, clientStage))
        }
    }

    p.future
  }

  /** Return the session to the pool.
    *
    * Depending on the state of the session and the nature of the pool, this may
    * either cache the session for future use or close it.
    */
  override def returnSession(session: HttpClientSession): Unit = {
    logger.info(s"Returning session $session")
    session match {
      case _ if session.isClosed => () // nop
      case _: Http2ClientSession => () // nop
      case proxy: Http1SessionProxy => addSessionToCache(proxy.id, proxy)
      case other => sys.error("The impossible happened!")
    }
  }

  /** Close the `SessionManager` and free any resources */
  override def close(): Unit = {
    logger.info(s"Closing session")
    ???
  }

  private[this] def addSessionToCache(id: ConnectionId, session: HttpClientSession): Unit = {
    val size = sessionCache.synchronized {
      val collection = sessionCache.get(id) match {
        case null =>
          val stack = new java.util.Stack[HttpClientSession]()
          sessionCache.put(id, stack)
          stack
        case some => some
      }

      collection.add(session)
      collection.size
    }
    logger.info(s"Added session $session. Now ${size} sessions for id $id")
  }
}
