package org.http4s.blaze.http

import scala.concurrent.Future

/** Generator and pool of http sessions */
trait ClientSessionManager {

  /** Acquire a session that is believe to be healthy.
    *
    * This may be a session that has already existed or it may be a new session.
    */
  def acquireSession(request: HttpRequest): Future[HttpClientSession]

  /** Return the session to the pool.
    *
    * Depending on the state of the session and the nature of the pool, this may
    * either cache the session for future use or close it.
    */
  def returnSession(session: HttpClientSession): Unit

  /** Close the `SessionManager` and free any resources */
  def close(): Unit
}
