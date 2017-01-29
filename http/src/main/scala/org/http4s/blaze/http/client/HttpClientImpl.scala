package org.http4s.blaze.http.client

import org.http4s.blaze.http.HttpRequest
import org.http4s.blaze.util.Execution

import scala.concurrent.Future

private[client] class HttpClientImpl(sessionPool: SessionManager) extends HttpClient {

  private implicit def ec = Execution.directec

  private class ReleaseableResponseImpl(
                                         session: HttpClientSession,
                                         resp: ClientResponse)
    extends ClientResponse(resp.code, resp.status, resp.headers, resp.body)
      with ReleaseableResponse {
    private[this] var released = false
    override def release(): Unit = synchronized {
      if (!released) {
        released = true
        sessionPool.returnSession(session)
      }
    }
  }

  override def unsafeDispatch(request: HttpRequest): Future[ReleaseableResponse] =
    sessionPool.acquireSession(request).flatMap { session =>
      val f = session(request).map { clientResponse =>
        new ReleaseableResponseImpl(session, clientResponse)
      }
      f.onFailure { case _ =>
        sessionPool.returnSession(session)
      }

      f
    }
}
