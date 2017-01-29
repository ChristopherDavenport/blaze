package org.http4s.blaze.http.client

import org.http4s.blaze.http.{HttpRequest, MessageBody}
import org.http4s.blaze.util.Execution

import scala.concurrent.ExecutionContext
import scala.concurrent.Future


/** Helper functions for the client */
trait ClientActions { self: HttpClient =>

  /** Perform a GET request
    *
    * @param url request URL
    * @param headers headers to attach to the request
    * @param action continuation with which to handle the request
    * @param ec `ExecutionContext` on which to run the request
    */
  def GET[A](url: String, headers: Seq[(String, String)] = Nil)
            (action: ClientResponse => Future[A])
            (implicit ec: ExecutionContext = Execution.trampoline): Future[A] = {

    val req = HttpRequest("GET", url, 1, 1, headers, MessageBody.emptyMessageBody)
    apply(req)(action)
  }
}
