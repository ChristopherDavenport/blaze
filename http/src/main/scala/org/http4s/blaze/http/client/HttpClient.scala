package org.http4s.blaze.http.client

import org.http4s.blaze.http.{HttpRequest, MessageBody}
import org.http4s.blaze.util.Execution

import scala.concurrent.Future


trait HttpClient extends ClientActions {

  /** ClientResponse that can be released */
  trait ReleaseableResponse extends ClientResponse {
    /** Releases the resources associated with this dispatch
      *
      * This may entail closing the connection or returning it to a connection
      * pool, depending on the client implementation and the state of the session
      * responsible for dispatching the associated request.
      *
      * @note `release()` is idempotent
      */
    def release(): Unit
  }

  /** Dispatch a request, resulting in the response
    *
    * @param request request to dispatch
    * @return the response. The cleanup of the resources associated with
    *         this dispatch are tied to the [[MessageBody]] of the [[ClientResponse]].
    *         Release of resources is triggered by complete consumption of the `MessageBody`
    *         or by calling `MessageBody.discard()`, whichever comes first.
    */
  def unsafeDispatch(request: HttpRequest): Future[ReleaseableResponse]

  /** Safely dispatch a client request
    *
    * Resources associated with this dispatch are guarenteed to be cleaned up during the
    * resolution of the returned `Future[T]`, regardless of if it is successful or not.
 *
    * @note The resources _may_ be cleaned up before the future resolves, but this is
    *       dependant on wheither
    */
  def apply[T](request: HttpRequest)(f: ClientResponse => Future[T]): Future[T] = {
    unsafeDispatch(request).flatMap { resp =>
      val result = f(resp)
      result.onComplete { _ => resp.release() }(Execution.directec)
      result
    }(Execution.directec)
  }
}