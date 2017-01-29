package org.http4s.blaze.http.client

import org.http4s.blaze.http.HttpRequest

import scala.concurrent.Future

/** Generator and pool of http sessions */
trait SessionManager {

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

  /** Releases the session resources and doesn't return it to the pool
    *
    * Useful for when the session is know to be contaminated
    */
  def releaseSession(session: HttpClientSession): Unit
}
