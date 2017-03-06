package org.http4s.blaze.http

import org.http4s.blaze.http.HttpClientSession.ReleaseableResponse
import org.http4s.blaze.util.Execution

import scala.concurrent.Future

// TODO: this is very tuned for HTTP/1.x. A dispatch doesn't get full control of a session in H2.
private[http] class HttpClientImpl(sessionPool: ClientSessionManager) extends HttpClient {

  private implicit def ec = Execution.directec

  private class ReleaseableResponseProxy(
      session: HttpClientSession,
      resp: ReleaseableResponse)
    extends ClientResponse(resp.code, resp.status, resp.headers, resp.body)
      with ReleaseableResponse {
    private[this] var released = false
    override def release(): Unit = synchronized {
      if (!released) {
        released = true
        resp.release()
        sessionPool.returnSession(session)
      }
    }
  }

  override def unsafeDispatch(request: HttpRequest): Future[ReleaseableResponse] =
    sessionPool.acquireSession(request).flatMap { session =>
      val f = session.dispatch(request)
        .map(new ReleaseableResponseProxy(session, _))
      f.onFailure { case _ => sessionPool.returnSession(session) }

      f
    }
}
