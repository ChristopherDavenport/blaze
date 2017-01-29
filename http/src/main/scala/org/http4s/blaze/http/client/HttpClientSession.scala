package org.http4s.blaze.http.client

import org.http4s.blaze.http.HttpRequest
import org.http4s.blaze.http.client.HttpClientSession.Status

import scala.concurrent.Future

trait HttpClientSession {
  /** Dispatch a request */
  def apply(request: HttpRequest): Future[ClientResponse]

  /** Get the status of session */
  def getStatus: Status

  /** An estimate for the current quality of the session
    *
    * @return a number with domain [0, 100] signifying the health or quality of
    *         the session. The scale is intended to be linear.
    */
  def quality: Int

  /** Close the session.
    *
    * This will generally entail closing the socket connection.
    */
  def close(): Unit
}

object HttpClientSession {
  sealed trait Status

  /** Able to dispatch now */
  case object Ready extends Status

  /** The session is busy and cannot dispatch a message at this time, but may be able to in the future */
  case object Busy extends Status

  /** The session will no longer be able to dispatch requests */
  case object Closed extends Status
}

