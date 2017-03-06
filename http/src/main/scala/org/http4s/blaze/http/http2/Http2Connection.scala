package org.http4s.blaze.http.http2

import org.http4s.blaze.http.Http2ClientSession
import org.http4s.blaze.http.http2.Http2Connection.ConnectionState
import org.http4s.blaze.pipeline.HeadStage

import scala.concurrent.Future
import scala.concurrent.duration.Duration


// TODO: we should rename this to Http2Session
trait Http2Connection {

//  def startSession(): Unit
  /** Get the current state of the session */
  def state: ConnectionState

  /** Signal that the session should shutdown within the grace period */
  def drainSession(gracePeriod: Duration, cause: Throwable): Unit

  /** Ping the peer, asynchronously returning the duration of the round trip
    *
    * @note the resulting duration includes the processing time at both peers.
    */
  def ping: Future[Duration]
}

trait Http2ClientConnection extends Http2Connection with Http2ClientSession {

  /** Create a new outbound stream
    *
    * Resources are not necessarily allocated to this stream, therefore it is
    * not guaranteed to succeed.
    */
  def newOutboundStream(): HeadStage[StreamMessage]
}

object Http2Connection {
  sealed trait ConnectionState
  case object Running extends ConnectionState

  sealed trait Closing extends ConnectionState
  case class Draining(deadline: Long, reason: Throwable) extends Closing
  case class Closed(reason: Throwable) extends Closing
}
